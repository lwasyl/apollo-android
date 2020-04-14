package com.apollographql.apollo

import com.apollographql.apollo.Utils.immediateExecutor
import com.apollographql.apollo.Utils.immediateExecutorService
import com.apollographql.apollo.Utils.mockResponse
import com.apollographql.apollo.api.Input
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.cache.normalized.lru.EvictionPolicy
import com.apollographql.apollo.cache.normalized.lru.LruNormalizedCacheFactory
import com.apollographql.apollo.coroutines.toDeferred
import com.apollographql.apollo.coroutines.toFlow
import com.apollographql.apollo.coroutines.toJob
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloParseException
import com.apollographql.apollo.fetcher.ApolloResponseFetchers
import com.apollographql.apollo.integration.caching.AllBarsQuery
import com.apollographql.apollo.integration.caching.AllFoosQuery
import com.apollographql.apollo.integration.httpcache.AllPlanetsQuery
import com.apollographql.apollo.integration.interceptor.AllFilmsQuery
import com.apollographql.apollo.integration.normalizer.EpisodeHeroNameQuery
import com.apollographql.apollo.integration.normalizer.EpisodeHeroWithDatesQuery
import com.apollographql.apollo.integration.normalizer.HeroAndFriendsNamesWithIDsQuery
import com.apollographql.apollo.integration.normalizer.ReviewsByEpisodeQuery
import com.apollographql.apollo.integration.normalizer.StarshipByIdQuery
import com.apollographql.apollo.integration.normalizer.type.Episode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch

class CoroutinesApolloTest {
  private lateinit var apolloClient: ApolloClient
  @get:Rule
  val server = MockWebServer()

  @Before
  fun setup() {
    val okHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher(immediateExecutorService()))
        .build()

    apolloClient = ApolloClient.builder()
        .serverUrl(server.url("/"))
        .dispatcher(immediateExecutor())
        .okHttpClient(okHttpClient)
        .normalizedCache(LruNormalizedCacheFactory(EvictionPolicy.NO_EVICTION), IdFieldCacheKeyResolver())
        .build()
  }

  @Test
  fun callDeferredProducesValue() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))

    val deferred = apolloClient.query(EpisodeHeroNameQuery(Input.fromNullable(Episode.EMPIRE))).toDeferred()
    runBlocking {
      assertThat(deferred.await().data!!.hero()!!.name()).isEqualTo("R2-D2")
    }
  }

  @Test
  fun prefetchCompletes() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))

    runBlocking {
      val job = apolloClient.prefetch(EpisodeHeroNameQuery(Input.fromNullable(Episode.EMPIRE)))
          .toJob()
      job.join()
    }
  }

  @Test
  fun prefetchIsCanceledWhenDisposed() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))

    runBlocking {
      val job = apolloClient.prefetch(EpisodeHeroNameQuery(Input.fromNullable(Episode.EMPIRE)))
          .toJob()
      job.cancel()
    }
  }

  @Test
  fun flowCanBeRead() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))

    val flow = apolloClient.query(EpisodeHeroNameQuery(Input.fromNullable(Episode.EMPIRE))).toFlow()

    runBlocking {
      val result = mutableListOf<Response<EpisodeHeroNameQuery.Data>>()
      flow.toList(result)
      assertThat(result.size).isEqualTo(1)
      assertThat(result[0].data?.hero()?.name()).isEqualTo("R2-D2")
    }
  }

  @Test
  fun flowError() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("nonsense"))

    val flow = apolloClient.query(EpisodeHeroNameQuery(Input.fromNullable(Episode.EMPIRE))).toFlow()

    runBlocking {
      val result = mutableListOf<Response<EpisodeHeroNameQuery.Data>>()
      try {
        flow.toList(result)
      } catch (e: ApolloException) {
        return@runBlocking
      }

      throw Exception("exception has not been thrown")
    }
  }

  @Test
  @ExperimentalCoroutinesApi
  fun callFlowRetry() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("nonsense"))
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))

    val response = runBlocking {
      apolloClient
          .query(EpisodeHeroNameQuery(Input.fromNullable(Episode.EMPIRE)))
          .toFlow()
          .retry(retries = 1)
          .single()
    }

    assertThat(response.data!!.hero()!!.name()).isEqualTo("R2-D2")
  }

  @Test
  @ExperimentalCoroutinesApi
  fun watcherFlowRetry() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("nonsense"))
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))

    val response = runBlocking {
      apolloClient
          .query(EpisodeHeroNameQuery(Input.fromNullable(Episode.EMPIRE)))
          .watcher()
          .toFlow()
          .retry(retries = 1)
          .first()
    }

    assertThat(response.data!!.hero()!!.name()).isEqualTo("R2-D2")
  }

  @Test
  @ExperimentalCoroutinesApi
  fun watcherFlowCancellationCancelsWatcher(): Unit = runBlocking {
    server.enqueue(mockResponse("FoosEmpty.json"))

    val firstValueReceivedLatch = CountDownLatch(1)

    val job = launch(Dispatchers.IO) {
      apolloClient
          .query(AllFoosQuery())
          .responseFetcher(ApolloResponseFetchers.CACHE_FIRST)
          .watcher()
          .toFlow(id = "FoosCall")
          .collect {
            if (firstValueReceivedLatch.count > 0) {
              firstValueReceivedLatch.countDown()
            } else {
              throw IllegalStateException("Shouldn't be reached")
            }
          }
    }

    println("Awaiting first flow")
    firstValueReceivedLatch.await()

    println("Cancelling first job")
    job.cancel()

    println("Clearing caches")
    apolloClient.clearNormalizedCache()
    apolloClient.clearHttpCache()

    println("Enqueueing second response")
    server.enqueue(mockResponse("BarsEmpty.json"))

    println("Calling second query")
    apolloClient
        .query(AllBarsQuery())
        .responseFetcher(ApolloResponseFetchers.NETWORK_ONLY)
        .watcher()
        .toFlow(id = "BarsCall")
        .first()
        .let { println("Received response: $it") }

    println("Second query value received")
    assertThat(server.requestCount).isEqualTo(2)
  }.let { Unit }

  companion object {

    private val FILE_EPISODE_HERO_NAME_WITH_ID = "EpisodeHeroNameResponseWithId.json"
    private val FILE_EPISODE_HERO_NAME_CHANGE = "EpisodeHeroNameResponseNameChange.json"
  }
}
