package com.lagradost.cloudstream3.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.addProgramsToContinueWatching
import com.lagradost.cloudstream3.utils.AppContextUtils.filterHomePageListByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.HOME_BANNER_CACHE
import com.lagradost.cloudstream3.utils.HOME_PAGE_CACHE
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllWatchStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getBookmarkedData
import com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount
import com.lagradost.cloudstream3.utils.DataStoreHelper.getResultWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class HomeViewModel : ViewModel() {
    companion object {
    }

    fun deleteBookmarks(list: List<SearchResponse>) {
        list.forEach { DataStoreHelper.deleteBookmarkedData(it.id) }
        loadStoredData()
    }

    var repo: APIRepository? = null

    private val _apiName = MutableLiveData<String>()
    val apiName: LiveData<String> = _apiName

    private val _currentAccount = MutableLiveData<DataStoreHelper.Account?>()
    val currentAccount: MutableLiveData<DataStoreHelper.Account?> = _currentAccount

    private val _randomItems = MutableLiveData<List<SearchResponse>?>(null)
    val randomItems: LiveData<List<SearchResponse>?> = _randomItems

    private var currentShuffledList: List<SearchResponse> = listOf()

    private fun autoloadRepo(): APIRepository {
        return APIRepository(apis.withLock { apis.first { it.hasMainPage } })
    }

    private val _availableWatchStatusTypes =
        MutableLiveData<Pair<Set<WatchType>, Set<WatchType>>>()
    val availableWatchStatusTypes: LiveData<Pair<Set<WatchType>, Set<WatchType>>> =
        _availableWatchStatusTypes
    private val _bookmarks = MutableLiveData<Pair<Boolean, List<SearchResponse>>>()
    val bookmarks: LiveData<Pair<Boolean, List<SearchResponse>>> = _bookmarks

    private val _preview = MutableLiveData<Resource<Pair<Boolean, List<LoadResponse>>>>()
    private val previewResponses = CopyOnWriteArrayList<LoadResponse>()
    private val previewResponsesAdded = ConcurrentHashMap.newKeySet<String>()

    val preview: LiveData<Resource<Pair<Boolean, List<LoadResponse>>>> = _preview

    fun loadStoredData(preferredWatchStatus: Set<WatchType>?) = viewModelScope.launchSafe {
        val watchStatusIds = withContext(Dispatchers.IO) {
            getAllWatchStateIds()?.map { id ->
                Pair(id, getResultWatchState(id))
            }
        }?.distinctBy { it.first } ?: return@launchSafe

        val length = WatchType.entries.size
        val currentWatchTypes = mutableSetOf<WatchType>()

        for (watch in watchStatusIds) {
            currentWatchTypes.add(watch.second)
            if (currentWatchTypes.size >= length) {
                break
            }
        }

        currentWatchTypes.remove(WatchType.NONE)

        if (currentWatchTypes.size <= 0) {
            DataStoreHelper.homeBookmarkedList = intArrayOf()
            _availableWatchStatusTypes.postValue(setOf<WatchType>() to setOf())
            _bookmarks.postValue(Pair(false, ArrayList()))
            return@launchSafe
        }

        val watchPrefNotNull = preferredWatchStatus ?: EnumSet.of(currentWatchTypes.first())
        //if (currentWatchTypes.any { watchPrefNotNull.contains(it) }) watchPrefNotNull else listOf(currentWatchTypes.first())

        DataStoreHelper.homeBookmarkedList = watchPrefNotNull.map { it.internalId }.toIntArray()
        _availableWatchStatusTypes.postValue(

            watchPrefNotNull to
                    currentWatchTypes,

            )

        val list = withContext(Dispatchers.IO) {
            watchStatusIds.filter { watchPrefNotNull.contains(it.second) }
                .mapNotNull { getBookmarkedData(it.first) }
                .sortedBy { -it.latestUpdatedTime }
        }
        _bookmarks.postValue(Pair(true, list))
    }

    private var onGoingLoad: Job? = null
    private var isCurrentlyLoadingName: String? = null
    private fun loadAndCancel(api: MainAPI) {
        //println("loaded ${api.name}")
        onGoingLoad?.cancel()
        isCurrentlyLoadingName = api.name
        onGoingLoad = load(api)
    }

    data class LoadingSearchResponse(
        @JsonProperty("name") override val name: String = "Loading...",
        @JsonProperty("url") override val url: String = "loading://",
        @JsonProperty("apiName") override val apiName: String = "",
        @JsonProperty("type") override var type: TvType? = null,
        @JsonProperty("posterUrl") override var posterUrl: String? = null,
        @JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("id") override var id: Int? = -1,
        @JsonProperty("quality") override var quality: SearchQuality? = null,
        @JsonProperty("score") override var score: Score? = null
    ) : SearchResponse

    data class LoadResponsePreview(
        override var name: String,
        override var url: String,
        override var apiName: String,
        override var type: TvType,
        override var posterUrl: String?,
        override var posterHeaders: Map<String, String>? = null,
        override var year: Int? = null,
        override var plot: String? = null,
        override var score: Score? = null,
        override var tags: List<String>? = null,
        override var duration: Int? = null,
        override var trailers: MutableList<com.lagradost.cloudstream3.TrailerData> = mutableListOf(),
        override var recommendations: List<SearchResponse>? = null,
        override var actors: List<com.lagradost.cloudstream3.ActorData>? = null,
        override var comingSoon: Boolean = false,
        override var syncData: MutableMap<String, String> = mutableMapOf(),
        override var backgroundPosterUrl: String? = null,
        override var logoUrl: String? = null,
        override var contentRating: String? = null,
        override var uniqueUrl: String = url,
    ) : LoadResponse

    data class ExpandableHomepageList(
        @JsonProperty("list") val list: HomePageList,
        @JsonProperty("currentPage") val currentPage: Int,
        @JsonProperty("hasNext") val hasNext: Boolean,
    )

    private val expandable = ConcurrentHashMap<String, ExpandableHomepageList>()
    private val _page =
        MutableLiveData<Resource<Map<String, ExpandableHomepageList>>>()
    val page: LiveData<Resource<Map<String, ExpandableHomepageList>>> = _page

    val lock: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun expandAndReturn(name: String): ExpandableHomepageList? {
        if (lock.contains(name)) return null
        lock += name

        repo?.apply {
            waitForHomeDelay()

            expandable[name]?.let { current ->
                debugAssert({ !current.hasNext }) {
                    "Expand called when not needed"
                }

                val nextPage = current.currentPage + 1
                val next = getMainPage(nextPage, mainPage.indexOfFirst { it.name == name })
                if (next is Resource.Success) {
                    next.value.filterNotNull().forEach { main ->
                        main.items.forEach { newList ->
                            val key = newList.name
                            expandable[key]?.let { innerCurrent ->
                                val updatedList = innerCurrent.list.copy(
                                    list = (innerCurrent.list.list + newList.list).distinctBy { it.url }
                                )
                                val updated = innerCurrent.copy(
                                    hasNext = main.hasNext,
                                    currentPage = nextPage,
                                    list = updatedList
                                )
                                expandable[key] = updated

                                // Update popup if it's currently showing this category
                                val currentPopup = _popup.value
                                if (currentPopup != null && currentPopup.first.list.name == key) {
                                    _popup.postValue(updated to currentPopup.second)
                                }
                            } ?: debugWarning {
                                "Expanded an item not in main load named $key, current list is ${expandable.keys}"
                            }
                        }
                    }
                } else {
                    expandable[name] = current.copy(hasNext = false)
                }
            }
            _page.postValue(Resource.Success(expandable))
        }

        lock -= name

        return expandable[name]
    }

    // this is soo over engineered, but idk how I can make it clean without making the main api harder to use :pensive:
    fun expand(name: String) = viewModelScope.launchSafe {
        expandAndReturn(name)
    }

    // returns the amount of items added and modifies current
    private suspend fun updatePreviewResponses(
        current: MutableList<LoadResponse>,
        alreadyAdded: MutableSet<String>,
        shuffledList: List<SearchResponse>,
        size: Int
    ): Int {
        var count = 0

        val addItems = arrayListOf<SearchResponse>()
        for (searchResponse in shuffledList) {
            if (!alreadyAdded.contains(searchResponse.url)) {
                addItems.add(searchResponse)
                alreadyAdded.add(searchResponse.url)
                if (++count >= size) {
                    break
                }
            }
        }

        val add = addItems.amap { searchResponse ->
            repo?.load(searchResponse.url)
        }.mapNotNull { if (it != null && it is Resource.Success) it.value else null }
        current.addAll(add)
        return add.size
    }

    private var addJob: Job? = null
    fun loadMoreHomeScrollResponses() {
        addJob = ioSafe {
            updatePreviewResponses(previewResponses, previewResponsesAdded, currentShuffledList, 1)
            _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
        }
    }

    private fun loadCache(apiName: String) {
        val bannerCacheKey = "$apiName/$HOME_BANNER_CACHE"
        val pageCacheKey = "$apiName/$HOME_PAGE_CACHE"

        // check if we already have real data in memory
        val hasRealData = expandable.values.any { row ->
            row.list.list.any { !it.url.startsWith("loading://") }
        }
        if (hasRealData && _apiName.value == apiName) return

        val cachedBanner = getKey<List<LoadResponse>>(bannerCacheKey)
        if (!cachedBanner.isNullOrEmpty()) {
            previewResponses.clear()
            previewResponses.addAll(cachedBanner)
            previewResponsesAdded.clear()
            previewResponsesAdded.addAll(cachedBanner.map { it.url })
            _preview.postValue(Resource.Success(true to previewResponses))
        } else {
            _preview.postValue(Resource.Loading())
            previewResponses.clear()
            previewResponsesAdded.clear()
        }

        val cachedPage = getKey<Map<String, ExpandableHomepageList>>(pageCacheKey)
        if (!cachedPage.isNullOrEmpty()) {
            expandable.clear()
            expandable.putAll(cachedPage)
            _page.postValue(Resource.Success(cachedPage))
        } else {
            // Post generic skeletons immediately if no cache
            val genericNames = listOf("Trending", "Popular", "Top Rated")
            val skeletons = genericNames.associateWith { name ->
                val response = List(6) { i -> LoadingSearchResponse(url = "loading://$name/$i") }
                ExpandableHomepageList(HomePageList(name, response), 1, false)
            }
            expandable.clear()
            expandable.putAll(skeletons)
            _page.postValue(Resource.Success(skeletons))
        }
    }

    private fun load(api: MainAPI): Job = ioSafe {
        repo = APIRepository(api)
        val currentRepo = this@HomeViewModel.repo ?: return@ioSafe

        _apiName.postValue(currentRepo.name)
        _randomItems.postValue(listOf())

        if (currentRepo.hasMainPage != true) {
            _page.postValue(Resource.Success(emptyMap()))
            _preview.postValue(Resource.Failure(false, "No homepage"))
            return@ioSafe
        }

        loadCache(api.name)

        // cancel the current preview expand as that is no longer relevant
        addJob?.cancel()

        val mainPageData = currentRepo.mainPage
        val bannerCacheKey = "${api.name}/$HOME_BANNER_CACHE"
        val pageCacheKey = "${api.name}/$HOME_PAGE_CACHE"
        val homeResults = arrayOfNulls<List<ExpandableHomepageList>>(maxOf(3, mainPageData.size))

        // Pre-fill from cache or create skeleton placeholders for at least 3 sections
        if (mainPageData.isNotEmpty()) {
            // Clear generic skeletons from expandable if we have structural info from mainPageData
            val hasRealData = expandable.values.any { row ->
                row.list.list.any { !it.url.startsWith("loading://") }
            }
            if (!hasRealData) {
                expandable.clear()
            }

            mainPageData.forEachIndexed { index, pageData ->
                val cached = expandable[pageData.name]
                if (cached != null) {
                    homeResults[index] = listOf(cached)
                } else if (index < 3) {
                    // Create a skeleton row if we don't have cache for the first 3 sections
                    val skeletons = List(6) { i -> LoadingSearchResponse(url = "loading://${pageData.name}/$i") }
                    val skeleton = ExpandableHomepageList(HomePageList(pageData.name, skeletons, pageData.horizontalImages), 1, false)
                    homeResults[index] = listOf(skeleton)
                    expandable[pageData.name] = skeleton
                }
            }
        } else {
            // No main page data yet, show generic skeletons
            val genericNames = listOf("Trending", "Popular", "Top Rated")
            val skeletons = genericNames.associateWith { name ->
                val response = List(6) { i -> LoadingSearchResponse(url = "loading://$name/$i") }
                ExpandableHomepageList(HomePageList(name, response), 1, false)
            }
            expandable.clear()
            expandable.putAll(skeletons)
            _page.postValue(Resource.Success(skeletons))
        }

        // Immediately post the initial state (cache + skeletons)
        val initialMap = LinkedHashMap<String, ExpandableHomepageList>()
        homeResults.forEach { list ->
            list?.forEach { item ->
                initialMap[item.list.name] = item
            }
        }
        if (initialMap.isNotEmpty()) {
            _page.postValue(Resource.Success(initialMap))
        }

        val allItems = mutableListOf<SearchResponse>()
        var previewJob: Job? = null
        val syncLock = Any()
        var lastFailure: Resource.Failure? = null

        fun processHomeResponse(res: Resource<List<HomePageResponse?>>, index: Int) {
            when (res) {
                is Resource.Success -> {
                    val newItems = mutableListOf<SearchResponse>()
                    val currentResults = mutableListOf<ExpandableHomepageList>()
                    res.value.filterNotNull().forEach { home ->
                        home.items.forEach { list ->
                            val filteredList = context?.filterHomePageListByFilmQuality(list) ?: list
                            val expandableList = ExpandableHomepageList(filteredList, 1, home.hasNext)
                            
                            expandable[list.name] = expandableList
                            
                            newItems.addAll(filteredList.list)
                            currentResults.add(expandableList)

                            // Update popup if it's currently showing this category
                            val currentPopup = _popup.value
                            if (currentPopup != null && currentPopup.first.list.name == list.name) {
                                _popup.postValue(expandableList to currentPopup.second)
                            }
                        }
                    }

                    synchronized(syncLock) {
                        // Update homeResults at the correct index
                        if (index < homeResults.size) {
                            homeResults[index] = currentResults
                        }

                        // Build ordered map for UI to prevent reshuffling
                        val orderedMap = LinkedHashMap<String, ExpandableHomepageList>()
                        homeResults.forEach { list ->
                            list?.forEach { item ->
                                orderedMap[item.list.name] = item
                            }
                        }
                        
                        // Handle potential extra items not in the initial homeResults structure
                        expandable.forEach { (name, list) ->
                            if (!orderedMap.containsKey(name)) {
                                val isSkeleton = list.list.list.all { it.url.startsWith("loading://") }
                                if (!isSkeleton) {
                                    orderedMap[name] = list
                                }
                            }
                        }

                        // Only post success if we have at least some items to show
                        if (orderedMap.isNotEmpty()) {
                            _page.postValue(Resource.Success(orderedMap))
                        }

                        setKey(pageCacheKey, orderedMap)

                        allItems.addAll(newItems)
                        if (allItems.isNotEmpty()) {
                            val distinctItems = allItems.distinctBy { it.url }
                            val shuffledList = distinctItems.shuffled()
                            val randomItems =
                                context?.filterSearchResultByFilmQuality(shuffledList)
                                    ?: shuffledList
                            currentShuffledList = randomItems
                            _randomItems.postValue(randomItems)

                            // 1. Post Instant Previews from Search Results
                            // This ensures the banner is populated in milliseconds!
                            val previews = randomItems.take(10).map {
                                LoadResponsePreview(
                                    name = it.name,
                                    url = it.url,
                                    apiName = it.apiName,
                                    type = it.type ?: TvType.Others,
                                    posterUrl = it.posterUrl,
                                    posterHeaders = it.posterHeaders,
                                    score = it.score
                                )
                            }
                            
                            // Only post previews if we don't have better data in previewResponses yet
                            if (previewJob == null && previewResponses.isEmpty()) {
                                _preview.postValue(Resource.Success(true to previews))
                            }

                            if (previewJob == null) {
                                previewJob = viewModelScope.launchSafe {
                                    // 2. Load the first 5 full metadata items ASAP
                                    if (updatePreviewResponses(
                                            previewResponses,
                                            previewResponsesAdded,
                                            currentShuffledList,
                                            5
                                        ) > 0
                                    ) {
                                        val data =
                                            (previewResponsesAdded.size < currentShuffledList.size) to previewResponses
                                        _preview.postValue(Resource.Success(data))
                                        setKey(bannerCacheKey, previewResponses.toList())
                                    }

                                    // 3. Load more items in the background
                                    if (updatePreviewResponses(
                                            previewResponses,
                                            previewResponsesAdded,
                                            currentShuffledList,
                                            5
                                        ) > 0
                                    ) {
                                        val data =
                                            (previewResponsesAdded.size < currentShuffledList.size) to previewResponses
                                        _preview.postValue(Resource.Success(data))
                                        setKey(bannerCacheKey, previewResponses.toList())
                                    }
                                }
                            }
                        }
                    }
                }

                is Resource.Failure -> {
                    synchronized(syncLock) {
                        lastFailure = res
                    }
                }

                else -> Unit
            }
        }

        try {
            val firstBatchSize = 4
            if (api.sequentialMainPage) {
                for (index in 0 until mainPageData.size) {
                    if (index > 0) delay(api.sequentialMainPageDelay)
                    val res = currentRepo.getMainPage(1, index)
                    processHomeResponse(res, index)
                }
            } else {
                // Prioritize the first batch to show content on screen ASAP
                coroutineScope {
                    for (index in 0 until minOf(firstBatchSize, mainPageData.size)) {
                        launch {
                            val res = currentRepo.getMainPage(1, index)
                            processHomeResponse(res, index)
                        }
                    }
                }

                // Load the rest in the background
                for (index in firstBatchSize until mainPageData.size) {
                    launch {
                        val res = currentRepo.getMainPage(1, index)
                        processHomeResponse(res, index)
                    }
                }
            }

            // If we have absolutely nothing after all attempts
            if (expandable.isEmpty()) {
                val failure = synchronized(syncLock) { lastFailure }
                if (failure != null) {
                    _page.postValue(failure)
                    _preview.postValue(failure)
                } else {
                    _page.postValue(Resource.Success(emptyMap()))
                    _preview.postValue(Resource.Failure(false, "No homepage responses"))
                }
            }
        } catch (e: Exception) {
            logError(e)
            if (expandable.isEmpty()) {
                _page.postValue(Resource.Failure(false, e.message ?: "Error loading homepage"))
            }
        }
        isCurrentlyLoadingName = null
    }

    fun click(callback: SearchClickCallback) {
        if (callback.action != SEARCH_ACTION_FOCUSED) {
            SearchHelper.handleSearchClickCallback(callback)
        }
    }

    private val _popup = MutableLiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?>(null)
    val popup: LiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?> = _popup

    fun popup(list: ExpandableHomepageList?, deleteCallback: (() -> Unit)? = null) {
        if (list == null)
            _popup.postValue(null)
        else
            _popup.postValue(list to deleteCallback)
    }

    private fun bookmarksUpdated(unused: Boolean) {
        reloadStored()
    }

    private fun afterPluginsLoaded(forceReload: Boolean) {
        loadAndCancel(DataStoreHelper.currentHomePage, forceReload)
    }

    private fun afterMainPluginsLoaded(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, false)
    }

    private fun reloadHome(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, true)
    }

    private fun reloadAccount(unused: Boolean = false) {
        _currentAccount.postValue(
            getCurrentAccount()
        )
    }

    init {
        MainActivity.bookmarksUpdatedEvent += ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent += ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent += ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent += ::reloadHome
        MainActivity.reloadAccountEvent += ::reloadAccount

        // Immediate cache/skeleton load on launch
        val lastApi = DataStoreHelper.currentHomePage
        if (lastApi != null && lastApi != noneApi.name) {
            _apiName.value = lastApi
            _preview.value = Resource.Loading()
            loadCache(lastApi)
        }
    }

    override fun onCleared() {
        MainActivity.bookmarksUpdatedEvent -= ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent -= ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent -= ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent -= ::reloadHome
        MainActivity.reloadAccountEvent -= ::reloadAccount
        super.onCleared()
    }

    fun queryTextSubmit(query: String) {
        QuickSearchFragment.pushSearch(
            query,
            repo?.name?.let { arrayOf(it) })
    }

    fun queryTextChange(newText: String) {
        // do nothing
    }

    fun loadStoredData() {
        val list = EnumSet.noneOf(WatchType::class.java)
        DataStoreHelper.homeBookmarkedList.map { WatchType.fromInternalId(it) }.let {
            list.addAll(it)
        }
        loadStoredData(list)
    }

    fun reloadStored() {
        loadStoredData()
    }

    fun click(load: LoadClickCallback) {
        loadResult(load.response.url, load.response.apiName, load.response.name, load.action)
    }

    // only save the key if it is from UI, as we don't want internal functions changing the setting
    fun loadAndCancel(
        preferredApiName: String?,
        forceReload: Boolean = true,
        fromUI: Boolean = false
    ) =
        ioSafe {
            val currentPage = page.value
            val currentLoading = isCurrentlyLoadingName

            // Check if we already have success with real data
            val hasRealData = currentPage is Resource.Success && 
                _apiName.value == preferredApiName &&
                currentPage.value.values.any { row ->
                    row.list.list.isNotEmpty() && row.list.list.any { !it.url.startsWith("loading://") }
                }

            if (!forceReload && (hasRealData || (currentLoading != null && currentLoading == preferredApiName))) {
                return@ioSafe
            }

            // If we have an api name, load cache/skeletons immediately on the background thread
            // but before the main load logic to ensure they appear ASAP.
            if (preferredApiName != null && preferredApiName != noneApi.name) {
                _apiName.postValue(preferredApiName)
                loadCache(preferredApiName)
            }

            val api = getApiFromNameNull(preferredApiName)
            if (preferredApiName == null || preferredApiName == noneApi.name) {
                // ...
                // just set to random
                if (fromUI) DataStoreHelper.currentHomePage = noneApi.name
                loadAndCancel(noneApi)
            } else if (preferredApiName == randomApi.name) {
                // randomize the api, if none exist like if not loaded or not installed
                // then use nothing
                val validAPIs = context?.filterProviderByPreferredMedia()
                if (validAPIs.isNullOrEmpty()) {
                    loadAndCancel(noneApi)
                } else {
                    val apiRandom = validAPIs.random()
                    loadAndCancel(apiRandom)
                    if (fromUI) DataStoreHelper.currentHomePage = apiRandom.name
                }
            } else if (api == null) {
                // API is not found aka not loaded or removed, post the loading
                // progress if waiting for plugins, otherwise nothing
                if (PluginManager.loadedOnlinePlugins || PluginManager.isSafeMode()) {
                    loadAndCancel(noneApi)
                } else {
                    // Cache/Skeletons already loaded above
                }
            } else {
                // if the api is found, then set it to it and save key
                if (fromUI) DataStoreHelper.currentHomePage = api.name
                loadAndCancel(api)
            }
            reloadAccount()
        }
}
