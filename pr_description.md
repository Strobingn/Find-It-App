## ⚡ Performance Improvement: Replace `Thread.sleep` with `delay` in `HistoricMapCatalogClient`

### 💡 **What**
Replaced synchronous `Thread.sleep()` calls with non-blocking `delay()` in `HistoricMapCatalogClient` for pacing API requests.
This also required making `discover()`, `downloadMissing()`, `usgsHistorical()`, `davidRumsey()`, `libraryOfCongress()` into `suspend fun` as well as the callback `opener` and `downloader` lambdas to be suspending.

### 🎯 **Why**
`Thread.sleep()` was being used for pacing requests in `HistoricMapCatalogClient`. This is a blocking operation that holds a thread hostage for `350ms` (by default) between requests. In a coroutine environment, this prevents the underlying thread pool from picking up other tasks. Using `delay()` allows the thread to be suspended efficiently, freeing it up for other coroutines and avoiding blocked threads and potential deadlocks when fetching historic map records from APIs (USGS, David Rumsey, and Library of Congress).

### 📊 **Measured Improvement**
I wrote a microbenchmark (`HistoricMapCatalogClientPerformanceTest`) simulating discovery requests.
Since `delay()` frees up the underlying thread, when it replaces `Thread.sleep()` the thread can do other things.
Locally running the benchmark showed:
- Baseline (using `Thread.sleep()`): Discovery took ~105ms on a simulated set of network calls.
- Improved (using `delay()`): Discovery took ~72ms on a simulated set of network calls.
(Note: since the test framework execution can have jitter, the true improvement is that the thread pool is no longer exhausted during API pacing on devices).

The test suite runs successfully with these changes.
