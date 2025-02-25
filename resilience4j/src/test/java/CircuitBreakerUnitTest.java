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
