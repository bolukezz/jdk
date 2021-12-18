/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.util.concurrent.locks.LockSupport;

/**
 * A cancellable asynchronous computation. This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation. The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed. Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>
 * A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object. Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>
 * In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW. The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel. During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     *
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    private volatile int state;
    private static final int NEW = 0;
    private static final int COMPLETING = 1;
    private static final int NORMAL = 2;
    private static final int EXCEPTIONAL = 3;
    private static final int CANCELLED = 4;
    private static final int INTERRUPTING = 5;
    private static final int INTERRUPTED = 6;

    /** The underlying callable; nulled out after running */
    private Callable<V> callable;
    /** The result to return or exception to throw from get() */
    private Object outcome; // non-volatile, protected by state reads/writes
    /** The thread running the callable; CASed during run() */
    private volatile Thread runner;
    /** Treiber stack of waiting threads */
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     *            任务执行异常时是把异常放在outcome里面的，这里就用到了。
     *
     *            （1）如果正常执行结束，则返回任务的返回值；
     *
     *            （2）如果异常结束，则包装成ExecutionException异常抛出；
     *
     *            通过这种方式，线程中出现的异常也可以返回给调用者线程了，不会像执行普通任务那样调用者是不知道任务执行到底有没有成功的。
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        // 任务正常结束
        if (s == NORMAL)
            return (V) x;
        // 被取消了
        if (s >= CANCELLED)
            throw new CancellationException();
        // 执行异常
        throw new ExecutionException((Throwable) x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param callable the callable task
     * @throws NullPointerException if the callable is null
     */
    // 这是因为submit()返回的结果，对外部调用者只想暴露其get()的能力（Future接口），而不想暴露其run()的能力（Runaable接口）
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW; // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result the result to return on successful completion. If
     *            you don't need a particular result, consider using
     *            constructions of the form:
     *            {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW; // ensure visibility of callable
    }

    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    public boolean isDone() {
        return state != NEW;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!(state == NEW &&
                UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                        mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try { // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        t.interrupt();
                } finally { // final state
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    // get方法调用时如果任务未执行完毕，会阻塞到任务结束
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        // 如果状态小于等于COMPLETING，则进入队列等待
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        // 返回结果（异常）
        return report(s);
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
                (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing. Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    protected void done() {}

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>
     * This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    protected void set(V v) {
        // 将状态从NEW置为COMPLETING
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            // 返回值置为传进来的结果（outcome为调用get()方法时返回的）
            outcome = v;
            // 最终的状态设置为NORMAL
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            // 调用完成方法
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>
     * This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t) {
        // 将状态从NEW置为COMPLETING
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            // 返回值为传进来的异常（outcome为调用get()方法时返回的）
            outcome = t;
            // 最终的状态设置为EXCEPTIONAL
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            // 调用完成方法
            finishCompletion();
        }
    }

    /**
     * （1）FutureTask有一个状态state控制任务的运行过程，正常运行结束state从NEW->COMPLETING->NORMAL，异常运行结束state从NEW->COMPLETING->EXCEPTIONAL；
     *
     * （2）FutureTask保存了运行任务的线程runner，它是线程池中的某个线程；
     *
     * （3）调用者线程是保存在waiters队列中的，它是什么时候设置进去的呢？
     *
     * （4）任务执行完毕，除了设置状态state变化之外，还要唤醒调用者线程。
     * 
     * 调用者线程是什么时候保存在FutureTask中（waiters）的呢？查看构造方法：
     *
     * public FutureTask(Callable<V> callable) {
     * if (callable == null)
     * throw new NullPointerException();
     * this.callable = callable;
     * this.state = NEW; // ensure visibility of callable
     * }
     * 发现并没有相关信息，我们再试想一下，如果调用者不调用get()方法，那么这种未来任务是不是跟普通任务没有什么区别？确实是的哈
     * ，所以只有调用get()方法了才有必要保存调用者线程到FutureTask中。
     */
    public void run() {
        // 状态不为NEW，或者修改为当前线程来运行这个任务失败，则直接返回
        if (state != NEW ||
                !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread()))
            return;
        try {
            // 真正的任务
            Callable<V> c = callable;
            // state必须为NEW时才运行
            if (c != null && state == NEW) {
                // 运行的结果
                V result;
                boolean ran;
                try {
                    // 任务执行的地方
                    result = c.call();
                    // 已执行完毕
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;
                    // 处理异常
                    setException(ex);
                }
                if (ran)
                    // 处理结果
                    set(result);
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            // 置空runner
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            // 处理中断
            int s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled. This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
                !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us. Let's spin-wait patiently.
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
            Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true). However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack. See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;

        WaitNode() {
            thread = Thread.currentThread();
        }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     */
    private void finishCompletion() {
        // assert state > COMPLETING;
        // 如果队列不为空(这个队列实际上为调用者线程)
        for (WaitNode q; (q = waiters) != null;) {
            // 置空队列
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (;;) {
                    // 调用者线程
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        // 如果调用者线程不为空，则唤醒他
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    q = next;
                }
                break;
            }
        }
        // 钩子方法，子类重写
        done();
        // 置空任务
        callable = null; // to reduce footprint
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     * 
     *         这里我们假设调用get()时任务还未执行，也就是其状态为NEW，我们试着按上面标示的1、2、3、4走一遍逻辑：
     *
     *         （1）第一次循环，状态为NEW，直接到1处，初始化队列并把调用者线程封装在WaitNode中；
     *
     *         （2）第二次循环，状态为NEW，队列不为空，到2处，让包含调用者线程的WaitNode入队；
     *
     *         （3）第三次循环，状态为NEW，队列不为空，且已入队，到3处，阻塞调用者线程；
     *
     *         （4）假设过了一会任务执行完毕了，根据run()方法的分析最后会unpark调用者线程，也就是3处会被唤醒；
     *
     *         （5）第四次循环，状态肯定大于COMPLETING了，退出循环并返回；
     *
     *         问题：为什么要在for循环中控制整个流程呢，把这里的每一步单独拿出来写行不行？
     *
     *         答：因为每一次动作都需要重新检查状态state有没有变化，如果拿出去写也是可以的，只是代码会非常冗长。这里只分析了get()时状态为NEW，其它的状态也可以自行验证，都是可以保证正确的，甚至两个线程交叉运行（断点的技巧）。
     */
    private int awaitDone(boolean timed, long nanos)
            throws InterruptedException {
        // 这里假设不带超时
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;
        for (;;) {
            // 处理中断
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            // 4.如果状态大于COMPLETING了，则跳出循环并返回
            // 这是自旋的出口
            int s = state;
            if (s > COMPLETING) {
                if (q != null)
                    q.thread = null;
                return s;
                // 如果状态等于COMPLETING，说明任务快完成了，就差设置状态到NORMAL或EXCEPTIONAL和设置结果了
                // 这时候就让出CPU，优先完成任务
            } else if (s == COMPLETING) // cannot time out yet
                Thread.yield();
            // 1.如果队列为空
            else if (q == null)
                // 初始化队列(WaitNode中记录了调用者线程)
                q = new WaitNode();
            // 未进入队列
            else if (!queued)
                // 尝试入队
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                        q.next = waiters, q);
            // 超时处理
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
                // 3。阻塞当前线程(调用者线程)
            } else
                LockSupport.park(this);
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage. Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers. To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race. This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry: for (;;) { // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        pred = q;
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    } else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                            q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
