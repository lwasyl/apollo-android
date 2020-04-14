package com.apollographql.apollo.coroutines

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloPrefetch
import com.apollographql.apollo.ApolloQueryWatcher
import com.apollographql.apollo.ApolloSubscriptionCall
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.exception.ApolloException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*


/**
 * Converts an [ApolloCall] to an [Flow].
 *
 * @param <T>  the value type.
 * @return a flow which emits [Responses<T>]
 */
@ExperimentalCoroutinesApi
fun <T> ApolloCall<T>.toFlow() = callbackFlow {
  clone().enqueue(
      object : ApolloCall.Callback<T>() {
        override fun onResponse(response: Response<T>) {
          runCatching {
            offer(response)
          }
        }

        override fun onFailure(e: ApolloException) {
          close(e)
        }

        override fun onStatusEvent(event: ApolloCall.StatusEvent) {
          if (event == ApolloCall.StatusEvent.COMPLETED) {
            close()
          }
        }
      }
  )
  awaitClose { this@toFlow.cancel() }
}

/**
 * Converts an [ApolloQueryWatcher] to an [Flow].
 *
 * @param <T>  the value type.
 * @return a flow which emits [Responses<T>]
 */
@ExperimentalCoroutinesApi
fun <T> ApolloQueryWatcher<T>.toFlow(id: String? = null) = callbackFlow {
  clone().enqueueAndWatch(
      object : ApolloCall.Callback<T>() {
        override fun onResponse(response: Response<T>) {
          println("[$id] Got response $response")
          runCatching {
            offer(response)
          }
        }

        override fun onFailure(e: ApolloException) {
          println("[$id] Got failure $e")
          close(e)
        }

        override fun onStatusEvent(event: ApolloCall.StatusEvent) {
          println("[$id] Got event $event")
          if (event == ApolloCall.StatusEvent.COMPLETED) {
            close()
          }
        }
      }
  )
  awaitClose { this@toFlow.cancel() }
}


/**
 * Converts an [ApolloCall] to an [Deferred]. This is a convenience method that will only return the first value emitted.
 * If the more than one response is required, for an example to retrieve cached and network response, use [toFlow] instead.
 *
 * @param <T>  the value type.
 * @return the deferred
 */
fun <T> ApolloCall<T>.toDeferred(): Deferred<Response<T>> {
  val deferred = CompletableDeferred<Response<T>>()

  deferred.invokeOnCompletion {
    if (deferred.isCancelled) {
      cancel()
    }
  }
  enqueue(object : ApolloCall.Callback<T>() {
    override fun onResponse(response: Response<T>) {
      if (deferred.isActive) {
        deferred.complete(response)
      }
    }

    override fun onFailure(e: ApolloException) {
      if (deferred.isActive) {
        deferred.completeExceptionally(e)
      }
    }
  })

  return deferred
}

/**
 * Converts an [ApolloSubscriptionCall] to an [Flow].
 *
 * @param <T>  the value type.
 * @return a flow which emits [Responses<T>]
 */
@ExperimentalCoroutinesApi
fun <T> ApolloSubscriptionCall<T>.toFlow(): Flow<Response<T>> = callbackFlow {
  clone().execute(
      object : ApolloSubscriptionCall.Callback<T> {
        override fun onConnected() {
        }

        override fun onResponse(response: Response<T>) {
          runCatching {
            channel.offer(response)
          }
        }

        override fun onFailure(e: ApolloException) {
          channel.close(e)
        }

        override fun onCompleted() {
          channel.close()
        }

        override fun onTerminated() {
          channel.close()
        }
      }
  )
  awaitClose { this@toFlow.cancel() }
}

/**
 * Converts an [ApolloPrefetch] to [Job].
 *
 * @param <T>  the value type.
 * @return the converted job
 */
fun ApolloPrefetch.toJob(): Job {
  val deferred = CompletableDeferred<Unit>()

  deferred.invokeOnCompletion {
    if (deferred.isCancelled) {
      cancel()
    }
  }

  enqueue(object : ApolloPrefetch.Callback() {
    override fun onSuccess() {
      if (deferred.isActive) {
        deferred.complete(Unit)
      }
    }

    override fun onFailure(e: ApolloException) {
      if (deferred.isActive) {
        deferred.completeExceptionally(e)
      }
    }
  })

  return deferred
}


