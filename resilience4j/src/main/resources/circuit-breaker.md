Circuit Breaker pattern is used to prevent a system from repeatedly trying to perform an action that is likely to fail, especially when the system is already under stress. It's inspired by the way electrical circuits work: if there is a fault, the circuit breaks to prevent further damage, and only after a certain amount of time or under specific conditions will it allow attempts again.

## Circuit Breaker States
- **CLOSED**: It indicates everything is normal and functioning correctly, and calls to the service proceed without any restrictions. If failures begin to accumulate (e.g., service responses are slow or error occurs), the circuit breaker may transition to the **OPEN** state.

- **OPEN**: When a certain threshold of failures (last N calls failed or last N seconds failed) is reached, the circuit breaker goes into the **OPEN** state. **OPEN** state rejects all calls which helps to prevent service from being overwhelmed with repeated failed requests. After a timeout or wait period, it transitions to **HALF_OPEN** to test if the service has recovered.

- **HALF_OPEN**: In this state, the circuit breaker allows a limited number of requests to pass through in order to see if the service has recovered. If these requests succeed, the circuit breaker goes back to the **CLOSED** state, and normal operations resume. If the test fails, it goes back to **OPEN**.

There are also special states:

- **METRICS_ONLY**: Everything works as usual, but failures are logged.

- **DISABLED**: The circuit breaker is effectively disabled; all calls are allowed.

- **FORCED_OPEN**: All requests are blocked, even if the system is healthy.

## Working of Circuit Breaker
Circuit Breaker tracks failures using a sliding window, which is a way to remember past request results.

### 1. Count-Based Sliding Window
- Keeps track of the last N requests.
- If too many of these requests fail, the circuit breaker opens.

### Time-Based Sliding Window
- Looks at requests from the last N seconds.
- Counts failures within this time window and decides whether to open the breaker.

## Failure Rate and Slow Call Rate Thresholds
The circuit breaker moves from **CLOSED** to **OPEN** when:

- The failure rate (percentage of failed requests) crosses a set limit (e.g., 50%).
- The slow call rate (percentage of slow responses) is too high (e.g., 50% of calls take longer than 5 seconds).

To prevent false alarms, the circuit breaker only checks these conditions after a minimum number of requests. For example, if the minimum required calls are 10, then the circuit breaker will not open even if all 9 calls fail—it needs at least 10 calls to make a decision.

While **OPEN**, all requests fail instantly with a `CallNotPermittedException`. After a wait time, the circuit breaker moves to **HALF_OPEN** and allows a few test requests.

- If failures continue, it goes back to **OPEN**.
- If test requests succeed, it moves back to **CLOSED** and resumes normal operation.

## Creating and Configuring a CircuitBreaker

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
    <version>2.3.0</version>
</dependency>
```

Resilience4j provides an in-memory **CircuitBreakerRegistry** that allows for managing CircuitBreaker instances.

```java
// creates a global default CircuitBreakerConfig for all of CircuitBreaker instances
CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
```

To provide custom global CircuitBreakerConfig, use CircuitBreakerConfig builder.

```java
CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
        .failureRateThreshold(50) // open circuit if failure rate exceeds 50%
        .slowCallRateThreshold(50) // open circuit if slow rate exceeds 50%
        .waitDurationInOpenState(Duration.ofMillis(1000)) // time the circuit breaker stays open (wait 1 sec before retrying)
        .slowCallDurationThreshold(Duration.ofSeconds(1000)) // slow call threshold (calls taking 2+ sec are slow)
        .minimumNumberOfCalls(10) // need at least 10 requests before opening circuit breaker
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED) // use time-based tracking
        .slidingWindowSize(5) // keep track of the last 5 seconds
        .recordExceptions(IOException.class, TimeoutException.class) // count these exceptions as failure
        .ignoreExceptions(CustomException.class) // ignore these exceptions
        .build();
 
CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);
```
From Resilience4j documentation: https://resilience4j.readme.io/docs/circuitbreaker#create-and-configure-a-circuitbreaker.

| Config Property                            | Default Value       | Description |
|--------------------------------------------|---------------------|-------------|
| `failureRateThreshold`                     | 50                  | Configures the failure rate threshold in percentage. When the failure rate is equal or greater than the threshold, the CircuitBreaker transitions to open and starts short-circuiting calls. |
| `slowCallRateThreshold`                    | 100                 | Configures a threshold in percentage. The CircuitBreaker considers a call as slow when the call duration is greater than `slowCallDurationThreshold`. When the percentage of slow calls is equal or greater than the threshold, the CircuitBreaker transitions to open and starts short-circuiting calls. |
| `slowCallDurationThreshold`                | 60000 [ms]          | Configures the duration threshold above which calls are considered as slow and increase the rate of slow calls. |
| `permittedNumberOfCallsInHalfOpenState`    | 10                  | Configures the number of permitted calls when the CircuitBreaker is half-open. |
| `maxWaitDurationInHalfOpenState`           | 0 [ms]              | Configures a maximum wait duration which controls the longest amount of time a CircuitBreaker could stay in Half Open state before switching to open. Value 0 means CircuitBreaker would wait indefinitely in HalfOpen State until all permitted calls have been completed. |
| `slidingWindowType`                        | COUNT_BASED         | Configures the type of the sliding window which is used to record the outcome of calls when the CircuitBreaker is closed. The sliding window can either be count-based or time-based. |
| `slidingWindowSize`                        | 100                 | Configures the size of the sliding window which is used to record the outcome of calls when the CircuitBreaker is closed. |
| `minimumNumberOfCalls`                     | 100                 | Configures the minimum number of calls required (per sliding window period) before the CircuitBreaker can calculate the error rate or slow call rate. If the minimum number of calls is not met, the CircuitBreaker will not transition to open even if all recorded calls fail. |
| `waitDurationInOpenState`                  | 60000 [ms]          | The time that the CircuitBreaker should wait before transitioning from open to half-open. |
| `automaticTransitionFromOpenToHalfOpenEnabled` | false          | If set to `true`, the CircuitBreaker will automatically transition from open to half-open state once `waitDurationInOpenState` passes. A thread monitors all instances of CircuitBreakers for this transition. If `false`, the transition to half-open happens only if a call is made after `waitDurationInOpenState` has passed, avoiding additional monitoring threads. |
| `recordExceptions`                         | empty               | A list of exceptions that are recorded as a failure, increasing the failure rate. Any exception matching or inheriting from the list counts as a failure unless explicitly ignored via `ignoreExceptions`. If a list is specified, all other exceptions count as success unless ignored. |
| `ignoreExceptions`                         | empty               | A list of exceptions that are ignored and neither count as a failure nor success. Any exception matching or inheriting from the list will not be considered a failure or success, even if it is part of `recordExceptions`. |
| `recordFailurePredicate`                   | `throwable -> true` | By default, all exceptions are recorded as failures. A custom Predicate can be defined to determine if an exception should be recorded as a failure. If the Predicate returns `true`, the exception counts as a failure. If `false`, it is treated as a success unless explicitly ignored. |
| `ignoreExceptionPredicate`                 | `throwable -> false` | By default, no exception is ignored. A custom Predicate can be defined to determine if an exception should be ignored. If the Predicate returns `true`, the exception is ignored and does not count as a failure or success. |

## Unit Testing the Circuit Breaker

```java
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
 
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
 
 
public class CircuitBreakerUnitTest {
 
    interface Processor {
        int process(int input);
    }
 
    private CircuitBreaker circuitBreaker;
 
    @BeforeEach
    void setup() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50) // Open if 50% of requests fail
                .slowCallRateThreshold(50) // Open if 50% of calls are slow
                .waitDurationInOpenState(Duration.ofSeconds(1)) // Wait 1 sec before retrying
                .slowCallDurationThreshold(Duration.ofSeconds(2)) // Calls taking 2+ sec are slow
                .permittedNumberOfCallsInHalfOpenState(3) // Allow 3 test calls in HALF_OPEN state
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .minimumNumberOfCalls(10) // Need at least 10 requests before making a decision
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED) // Use time-based tracking
                .slidingWindowSize(5) // Track last 5 seconds of requests
                .recordExceptions(IOException.class, TimeoutException.class, RuntimeException.class) // Count these as failures
                .ignoreExceptions(IllegalArgumentException.class) // Ignore these exceptions
                .build();
 
        circuitBreaker = CircuitBreaker.of("testCircuit", config);
    }
 
    @Test
    void testCircuitBreakerOpensOnFailures() {
        /**
         * The circuit breaker opens after 10 failed requests because the failure rate exceeds the configured threshold
         * of 50%. With the minimumNumberOfCalls(10) setting, after 10 calls, the failure rate is 100%, which surpasses
         * the failureRateThreshold(50). As a result, the circuit breaker transitions to the open state, blocking further
         * requests until the retry conditions are met.
         */
        IntStream.range(0, 10).forEach(i -> circuitBreaker.onError(0, TimeUnit.SECONDS, new IOException("Service failure")));
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }
 
    @Test
    void testCircuitBreakerClosesAfterSuccessfulCalls() throws InterruptedException {
        // open circuit breaker first
        IntStream.range(0, 10).forEach(i -> circuitBreaker.onError(0, TimeUnit.SECONDS, new IOException("Service failure")));
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
 
        /**
         * After the circuit breaker has opened due to exceeding the failure rate threshold, you wait for 1.1 seconds (Thread.sleep(1100)).
         * The waitDurationInOpenState(1 second) setting in the configuration specifies that after being open for 1
         * second, the circuit breaker transitions to the HALF_OPEN state. In this state, it allows a limited number of
         * test calls (3 in this case, as per permittedNumberOfCallsInHalfOpenState(3)) to check if the service is
         * functioning properly again.
         */
        Thread.sleep(1300);
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
 
        /**
         * The next step sends 3 successful requests using circuitBreaker.onSuccess(0).
         * Since the allowed 3 test calls in HALF_OPEN state were successful, the circuit breaker determines that the
         * service has recovered. As a result, the circuit breaker transitions to the CLOSED state, meaning it will
         * allow normal requests again without restriction.
         */
        IntStream.range(0, 3).forEach(i -> circuitBreaker.onSuccess(0, TimeUnit.SECONDS));
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }
 
    @Test
    void testCallNotPermittedWhenOpen() {
        // simulate slow calls, onSuccess has 3 sec elapsed duration time which exceeds 2 sec threshold
        IntStream.range(0, 10).forEach(i -> circuitBreaker.onSuccess(3000, TimeUnit.SECONDS));
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }
 
    @Test
    void testCircuitBreakerThreadSafety() throws InterruptedException {
        // crates a pool of 10 threads, so multiple operations can run at same time
        ExecutorService executorService = Executors.newFixedThreadPool(10);
 
        // safely track the number of failures across threads (since multiple threads are modifying it
        // AtomicInteger ensures thread safety
        AtomicInteger failureCount = new AtomicInteger(0);
 
        Runnable task = () -> {
            try {
                if (ThreadLocalRandom.current().nextBoolean()) { // ensures a 50% chance of success or failure
                    circuitBreaker.onSuccess(0, TimeUnit.SECONDS);
                } else {
                    circuitBreaker.onError(0, TimeUnit.SECONDS, new IOException("Failure"));
                    failureCount.incrementAndGet();
                }
            } catch (Exception ignored) {
            }
        };
 
        // submit 20 task to the thread pool, each task runs the above logic in a separate thread
        // randomly marking success or failure
        IntStream.range(0, 20).forEach(i -> executorService.submit(task));
 
        // prevents new tasks from being added
        executorService.shutdown();
        // waits up to 5 seconds for all tasks to finish
        executorService.awaitTermination(5, TimeUnit.SECONDS);
 
        assertEquals(circuitBreaker.getMetrics().getNumberOfFailedCalls(), failureCount.get());
    }
 
    @Test
    void testStateTransitionEvents() {
        // CopyOnWriteArrayList<> to safely handle concurrent modifications, ensuring no conflicts if
        // multiple threads modify it simultaneously
        List<CircuitBreakerEvent> eventList = new CopyOnWriteArrayList<>();
        circuitBreaker.getEventPublisher().onStateTransition(eventList::add);
        IntStream.range(0, 10).forEach(i -> circuitBreaker.onError(0, TimeUnit.SECONDS, new IOException("Failure")));
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertFalse(eventList.isEmpty());
    }
 
    @Test
    public void testCircuitBreakerStopsExecutingAfterFailures() throws InterruptedException {
        Processor processor = mock(Processor.class);
 
        /**
         Decorate function allows you to wrap (decorate) a function with a CircuitBreaker so that the function
         execution is monitored and controlled. If failures exceed the defined threshold, the CircuitBreaker stops
         executing the function and instead fails fast.
         */
        Function<Integer, Integer> function = CircuitBreaker.decorateFunction(circuitBreaker, processor::process);
 
        when(processor.process(anyInt())).thenThrow(new RuntimeException("Failure"));
        IntStream.range(1, 11).forEach(i -> {
            try {
                /**
                 * When apply(i) is executed:
                 * The CircuitBreaker checks if it's in the CLOSED, OPEN, or HALF-OPEN state.
                 * If CLOSED (Normal Operation), the function processor.process(i) is executed. If it succeeds, the
                 * CircuitBreaker records it as a successful call. If it fails (throws an exception), the CircuitBreaker
                 * records it as a failure.
                 *
                 * If OPEN (Failure Threshold Exceeded) , the function is not executed at all. The CircuitBreaker fails
                 * fast by throwing an exception immediately.
                 *
                 * If HALF-OPEN (Testing Phase), the function is executed for a limited number of test calls. If calls
                 * succeed, the CircuitBreaker transitions to CLOSED. If calls fail, it goes back to OPEN.
                 */
                function.apply(i);
            } catch (Exception ignored) {
            }
        });
 
        verify(processor, times(10)).process(anyInt());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }
}
```
