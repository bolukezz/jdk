/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.LockSupport;

/**
 * A capability-based lock with three modes for controlling read/write
 * access. The state of a StampedLock consists of a version and mode.
 * Lock acquisition methods return a stamp that represents and
 * controls access with respect to a lock state; "try" versions of
 * these methods may instead return the special value zero to
 * represent failure to acquire access. Lock release and conversion
 * methods require stamps as arguments, and fail if they do not match
 * the state of the lock. The three modes are:
 *
 * <ul>
 *
 * <li><b>Writing.</b> Method {@link #writeLock} possibly blocks
 * waiting for exclusive access, returning a stamp that can be used
 * in method {@link #unlockWrite} to release the lock. Untimed and
 * timed versions of {@code tryWriteLock} are also provided. When
 * the lock is held in write mode, no read locks may be obtained,
 * and all optimistic read validations will fail.</li>
 *
 * <li><b>Reading.</b> Method {@link #readLock} possibly blocks
 * waiting for non-exclusive access, returning a stamp that can be
 * used in method {@link #unlockRead} to release the lock. Untimed
 * and timed versions of {@code tryReadLock} are also provided.</li>
 *
 * <li><b>Optimistic Reading.</b> Method {@link #tryOptimisticRead}
 * returns a non-zero stamp only if the lock is not currently held
 * in write mode. Method {@link #validate} returns true if the lock
 * has not been acquired in write mode since obtaining a given
 * stamp. This mode can be thought of as an extremely weak version
 * of a read-lock, that can be broken by a writer at any time. The
 * use of optimistic mode for short read-only code segments often
 * reduces contention and improves throughput. However, its use is
 * inherently fragile. Optimistic read sections should only read
 * fields and hold them in local variables for later use after
 * validation. Fields read while in optimistic mode may be wildly
 * inconsistent, so usage applies only when you are familiar enough
 * with data representations to check consistency and/or repeatedly
 * invoke method {@code validate()}. For example, such steps are
 * typically required when first reading an object or array
 * reference, and then accessing one of its fields, elements or
 * methods.</li>
 *
 * </ul>
 *
 * <p>
 * This class also supports methods that conditionally provide
 * conversions across the three modes. For example, method {@link
 * #tryConvertToWriteLock} attempts to "upgrade" a mode, returning
 * a valid write stamp if (1) already in writing mode (2) in reading
 * mode and there are no other readers or (3) in optimistic mode and
 * the lock is available. The forms of these methods are designed to
 * help reduce some of the code bloat that otherwise occurs in
 * retry-based designs.
 *
 * <p>
 * StampedLocks are designed for use as internal utilities in the
 * development of thread-safe components. Their use relies on
 * knowledge of the internal properties of the data, objects, and
 * methods they are protecting. They are not reentrant, so locked
 * bodies should not call other unknown methods that may try to
 * re-acquire locks (although you may pass a stamp to other methods
 * that can use or convert it). The use of read lock modes relies on
 * the associated code sections being side-effect-free. Unvalidated
 * optimistic read sections cannot call methods that are not known to
 * tolerate potential inconsistencies. Stamps use finite
 * representations, and are not cryptographically secure (i.e., a
 * valid stamp may be guessable). Stamp values may recycle after (no
 * sooner than) one year of continuous operation. A stamp held without
 * use or validation for longer than this period may fail to validate
 * correctly. StampedLocks are serializable, but always deserialize
 * into initial unlocked state, so they are not useful for remote
 * locking.
 *
 * <p>
 * The scheduling policy of StampedLock does not consistently
 * prefer readers over writers or vice versa. All "try" methods are
 * best-effort and do not necessarily conform to any scheduling or
 * fairness policy. A zero return from any "try" method for acquiring
 * or converting locks does not carry any information about the state
 * of the lock; a subsequent invocation may succeed.
 *
 * <p>
 * Because it supports coordinated usage across multiple lock
 * modes, this class does not directly implement the {@link Lock} or
 * {@link ReadWriteLock} interfaces. However, a StampedLock may be
 * viewed {@link #asReadLock()}, {@link #asWriteLock()}, or {@link
 * #asReadWriteLock()} in applications requiring only the associated
 * set of functionality.
 *
 * <p>
 * <b>Sample Usage.</b> The following illustrates some usage idioms
 * in a class that maintains simple two-dimensional points. The sample
 * code illustrates some try/catch conventions even though they are
 * not strictly needed here because no exceptions can occur in their
 * bodies.<br>
 *
 * <pre>
 * {
 *     &#64;code
 *     class Point {
 *         private double x, y;
 *         private final StampedLock sl = new StampedLock();
 *
 *         void move(double deltaX, double deltaY) { // an exclusively locked method
 *             long stamp = sl.writeLock();
 *             try {
 *                 x += deltaX;
 *                 y += deltaY;
 *             } finally {
 *                 sl.unlockWrite(stamp);
 *             }
 *         }
 *
 *         double distanceFromOrigin() { // A read-only method
 *             long stamp = sl.tryOptimisticRead();
 *             double currentX = x, currentY = y;
 *             if (!sl.validate(stamp)) {
 *                 stamp = sl.readLock();
 *                 try {
 *                     currentX = x;
 *                     currentY = y;
 *                 } finally {
 *                     sl.unlockRead(stamp);
 *                 }
 *             }
 *             return Math.sqrt(currentX * currentX + currentY * currentY);
 *         }
 *
 *         void moveIfAtOrigin(double newX, double newY) { // upgrade
 *             // Could instead start with optimistic, not read mode
 *             long stamp = sl.readLock();
 *             try {
 *                 while (x == 0.0 && y == 0.0) {
 *                     long ws = sl.tryConvertToWriteLock(stamp);
 *                     if (ws != 0L) {
 *                         stamp = ws;
 *                         x = newX;
 *                         y = newY;
 *                         break;
 *                     } else {
 *                         sl.unlockRead(stamp);
 *                         stamp = sl.writeLock();
 *                     }
 *                 }
 *             } finally {
 *                 sl.unlock(stamp);
 *             }
 *         }
 *     }
 * }
 * </pre>
 *
 * @since 1.8
 * @author Doug Lea
 */


/**
 * （1）StampedLock也是一种读写锁，它不是基于AQS实现的；
 *
 * （2）StampedLock相较于ReentrantReadWriteLock多了一种乐观读的模式，以及读锁转化为写锁的方法；
 *
 * （3）StampedLock的state存储的是版本号，确切地说是高24位存储的是版本号，写锁的释放会增加其版本号，读锁不会；
 *
 * （4）StampedLock的低7位存储的读锁被获取的次数，第8位存储的是写锁被获取的次数；
 *
 * （5）StampedLock不是可重入锁，因为只有第8位标识写锁被获取了，并不能重复获取；
 *
 * （6）StampedLock中获取锁的过程使用了大量的自旋操作，对于短任务的执行会比较高效，长任务的执行会浪费大量CPU；
 *
 * （7）StampedLock不能实现条件锁；
 *
 *
 *
 * StampedLock与ReentrantReadWriteLock的对比？
 *
 * 答：StampedLock与ReentrantReadWriteLock作为两种不同的读写锁方式，彤哥大致归纳了它们的异同点：
 *
 * （1）两者都有获取读锁、获取写锁、释放读锁、释放写锁的方法，这是相同点；
 *
 * （2）两者的结构基本类似，都是使用state + CLH队列；
 *
 * （3）前者的state分成三段，高24位存储版本号、低7位存储读锁被获取的次数、第8位存储写锁被获取的次数；
 *
 * （4）后者的state分成两段，高16位存储读锁被获取的次数，低16位存储写锁被获取的次数；
 *
 * （5）前者的CLH队列可以看成是变异的CLH队列，连续的读线程只有首个节点存储在队列中，其它的节点存储的首个节点的cowait栈中；
 *
 * （6）后者的CLH队列是正常的CLH队列，所有的节点都在这个队列中；
 *
 * （7）前者获取锁的过程中有判断首尾节点是否相同，也就是是不是快轮到自己了，如果是则不断自旋，所以适合执行短任务；
 *
 * （8）后者获取锁的过程中非公平模式下会做有限次尝试；
 *
 * （9）前者只有非公平模式，一上来就尝试获取锁；
 *
 * （10）前者唤醒读锁是一次性唤醒连续的读锁的，而且其它线程还会协助唤醒；
 *
 * （11）后者是一个接着一个地唤醒的；
 *
 * （12）前者有乐观读的模式，乐观读的实现是通过判断state的高25位是否有变化来实现的；
 *
 * （13）前者各种模式可以互转，类似tryConvertToXxx()方法；
 *
 * （14）前者写锁不可重入，后者写锁可重入；
 *
 * （15）前者无法实现条件锁，后者可以实现条件锁；
 */
public class StampedLock implements java.io.Serializable {
    /*
     * Algorithmic notes:
     * The design employs elements of Sequence locks
     * (as used in linux kernels; see Lameter's
     * http://www.lameter.com/gelato2005.pdf
     * and elsewhere; see
     * Boehm's http://www.hpl.hp.com/techreports/2012/HPL-2012-68.html)
     * and Ordered RW locks (see Shirako et al
     * http://dl.acm.org/citation.cfm?id=2312015)
     * Conceptually, the primary state of the lock includes a sequence
     * number that is odd when write-locked and even otherwise.
     * However, this is offset by a reader count that is non-zero when
     * read-locked. The read count is ignored when validating
     * "optimistic" seqlock-reader-style stamps. Because we must use
     * a small finite number of bits (currently 7) for readers, a
     * supplementary reader overflow word is used when the number of
     * readers exceeds the count field. We do this by treating the max
     * reader count value (RBITS) as a spinlock protecting overflow
     * updates.
     * Waiters use a modified form of CLH lock used in
     * AbstractQueuedSynchronizer (see its internal documentation for
     * a fuller account), where each node is tagged (field mode) as
     * either a reader or writer. Sets of waiting readers are grouped
     * (linked) under a common node (field cowait) so act as a single
     * node with respect to most CLH mechanics. By virtue of the
     * queue structure, wait nodes need not actually carry sequence
     * numbers; we know each is greater than its predecessor. This
     * simplifies the scheduling policy to a mainly-FIFO scheme that
     * incorporates elements of Phase-Fair locks (see Brandenburg &
     * Anderson, especially http://www.cs.unc.edu/~bbb/diss/). In
     * particular, we use the phase-fair anti-barging rule: If an
     * incoming reader arrives while read lock is held but there is a
     * queued writer, this incoming reader is queued. (This rule is
     * responsible for some of the complexity of method acquireRead,
     * but without it, the lock becomes highly unfair.) Method release
     * does not (and sometimes cannot) itself wake up cowaiters. This
     * is done by the primary thread, but helped by any other threads
     * with nothing better to do in methods acquireRead and
     * acquireWrite.
     * These rules apply to threads actually queued. All tryLock forms
     * opportunistically try to acquire locks regardless of preference
     * rules, and so may "barge" their way in. Randomized spinning is
     * used in the acquire methods to reduce (increasingly expensive)
     * context switching while also avoiding sustained memory
     * thrashing among many threads. We limit spins to the head of
     * queue. A thread spin-waits up to SPINS times (where each
     * iteration decreases spin count with 50% probability) before
     * blocking. If, upon wakening it fails to obtain lock, and is
     * still (or becomes) the first waiting thread (which indicates
     * that some other thread barged and obtained lock), it escalates
     * spins (up to MAX_HEAD_SPINS) to reduce the likelihood of
     * continually losing to barging threads.
     * Nearly all of these mechanics are carried out in methods
     * acquireWrite and acquireRead, that, as typical of such code,
     * sprawl out because actions and retries rely on consistent sets
     * of locally cached reads.
     * As noted in Boehm's paper (above), sequence validation (mainly
     * method validate()) requires stricter ordering rules than apply
     * to normal volatile reads (of "state"). To force orderings of
     * reads before a validation and the validation itself in those
     * cases where this is not already forced, we use
     * Unsafe.loadFence.
     * The memory layout keeps lock state and queue pointers together
     * (normally on the same cache line). This usually works well for
     * read-mostly loads. In most other cases, the natural tendency of
     * adaptive-spin CLH locks to reduce memory contention lessens
     * motivation to further spread out contended locations, but might
     * be subject to future improvements.
     */

    private static final long serialVersionUID = -6001602636862214147L;

    /** Number of processors, for spin control */
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    /** Maximum number of retries before enqueuing on acquisition */
    private static final int SPINS = (NCPU > 1) ? 1 << 6 : 0;

    /** Maximum number of retries before blocking at head on acquisition */
    private static final int HEAD_SPINS = (NCPU > 1) ? 1 << 10 : 0;

    /** Maximum number of retries before re-blocking */
    private static final int MAX_HEAD_SPINS = (NCPU > 1) ? 1 << 16 : 0;

    /** The period for yielding when waiting for overflow spinlock */
    private static final int OVERFLOW_YIELD_RATE = 7; // must be power 2 - 1

    /** The number of bits to use for reader count before overflowing */
    // 读线程的个数占有低7位。
    private static final int LG_READERS = 7;

    // Values for lock state and stamp operations
    // 读线程个数每次增加的单位
    private static final long RUNIT = 1L;
    // 写线程个数所在的位置
    private static final long WBIT = 1L << LG_READERS;
    // 读线程个数所在的位置
    private static final long RBITS = WBIT - 1L;
    // 最大读线程个数
    private static final long RFULL = RBITS - 1L;
    // 读线程个数和写线程个数的掩码
    private static final long ABITS = RBITS | WBIT;
    // 读线程个数的反数，高25位全部为1
    private static final long SBITS = ~RBITS; // note overlap with ABITS

    // Initial value for lock state; avoid failure value zero
    // state的初始值 为256 1 0000 0000
    private static final long ORIGIN = WBIT << 1;

    // Special value from cancelled acquire methods so caller can throw IE
    private static final long INTERRUPTED = 1L;

    // Values for node status; order matters
    private static final int WAITING = -1;
    private static final int CANCELLED = 1;

    // Modes for nodes (int not boolean to allow arithmetic)
    private static final int RMODE = 0;
    private static final int WMODE = 1;

    /** Wait nodes */
    static final class WNode {
        // 前一个节点
        volatile WNode prev;
        // 后一个节点
        volatile WNode next;
        // 读线程所用的链表（实际是一个栈结果
        volatile WNode cowait; // list of linked readers
        // 阻塞的线程
        volatile Thread thread; // non-null while possibly parked
        // 状态
        volatile int status; // 0, WAITING, or CANCELLED
        // 读模式还是写模式
        final int mode; // RMODE or WMODE

        WNode(int m, WNode p) {
            mode = m;
            prev = p;
        }
    }

    /** Head of CLH queue */
    private transient volatile WNode whead;
    /** Tail (last) of CLH queue */
    private transient volatile WNode wtail;

    // views
    transient ReadLockView readLockView;
    transient WriteLockView writeLockView;
    transient ReadWriteLockView readWriteLockView;

    /** Lock sequence/state */
    private transient volatile long state;
    /** extra reader count when state read count saturated */
    private transient int readerOverflow;

    /**
     * Creates a new lock, initially in unlocked state.
     */
    public StampedLock() {
        state = ORIGIN;
    }

    /**
     * Exclusively acquires the lock, blocking if necessary
     * until available.
     *
     * @return a stamp that can be used to unlock or convert mode
     */
    /**
     * 此时state为初始状态，与ABITS与运算后的值为0，所以执行后面的CAS方法，s + WBITS的值为384 = 1 1000 0000。
     * 到这里我们大胆猜测：state的高24位存储的是版本号，低8位存储的是是否有加锁，第8位存储的是写锁，低7位存储的是读锁被获取的次数
     * ，而且如果只有第8位存储写锁的话，那么写锁只能被获取一次，也就不可能重入了。
     * 
     * @return
     */
    public long writeLock() {
        long s, next; // bypass acquireWrite in fully unlocked case only
        // ABITS=255 =1111 1111
        // WBITS=128 =1000 0000
        // state与ABIT如果等于0，尝试原子更新state的值加上WBITS
        // 如果成功则返回更新的值，如果失败调用acquireWrite()方法
        return ((((s = state) & ABITS) == 0L &&
                U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ? next : acquireWrite(false, 0L));
    }

    /**
     * Exclusively acquires the lock if it is immediately available.
     *
     * @return a stamp that can be used to unlock or convert mode,
     *         or zero if the lock is not available
     */
    public long tryWriteLock() {
        long s, next;
        // state与ABIT如果等于0，尝试原子更新state的值加上WBITS
        return ((((s = state) & ABITS) == 0L &&
                U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ? next : 0L);
    }

    /**
     * Exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long,TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a stamp that can be used to unlock or convert mode,
     *         or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     *             before acquiring the lock
     */
    public long tryWriteLock(long time, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(time);
        // 这块和writeLock一致，只不过是这块调用acquireWrite只会等待deadline时间
        if (!Thread.interrupted()) {
            long next, deadline;
            if ((next = tryWriteLock()) != 0L)
                return next;
            if (nanos <= 0L)
                return 0L;
            if ((deadline = System.nanoTime() + nanos) == 0L)
                deadline = 1L;
            if ((next = acquireWrite(true, deadline)) != INTERRUPTED)
                return next;
        }
        throw new InterruptedException();
    }

    /**
     * Exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     *             before acquiring the lock
     */
    public long writeLockInterruptibly() throws InterruptedException {
        long next;
        if (!Thread.interrupted() &&
                (next = acquireWrite(true, 0L)) != INTERRUPTED)
            return next;
        throw new InterruptedException();
    }

    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available.
     *
     * @return a stamp that can be used to unlock or convert mode
     */
    public long readLock() {
        long s = state, next; // bypass acquireRead on common uncontended case
        // 在这里判断如果当前没有写线程并且判断当前读线程有没有到达最大个数
        // 然后CAS使当前state的状态+1,如果没有获取到则调用acquireRead()继续获取读锁
        return ((whead == wtail && (s & ABITS) < RFULL &&
                U.compareAndSwapLong(this, STATE, s, next = s + RUNIT)) ? next : acquireRead(false, 0L));
    }

    /**
     * Non-exclusively acquires the lock if it is immediately available.
     *
     * @return a stamp that can be used to unlock or convert mode,
     *         or zero if the lock is not available
     */
    public long tryReadLock() {
        for (;;) {
            long s, m, next;
            if ((m = (s = state) & ABITS) == WBIT)
                return 0L;
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                    return next;
            } else if ((next = tryIncReaderOverflow(s)) != 0L)
                return next;
        }
    }

    /**
     * Non-exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long,TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a stamp that can be used to unlock or convert mode,
     *         or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     *             before acquiring the lock
     */
    public long tryReadLock(long time, TimeUnit unit)
            throws InterruptedException {
        long s, m, next, deadline;
        long nanos = unit.toNanos(time);
        if (!Thread.interrupted()) {
            if ((m = (s = state) & ABITS) != WBIT) {
                if (m < RFULL) {
                    if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                        return next;
                } else if ((next = tryIncReaderOverflow(s)) != 0L)
                    return next;
            }
            if (nanos <= 0L)
                return 0L;
            if ((deadline = System.nanoTime() + nanos) == 0L)
                deadline = 1L;
            if ((next = acquireRead(true, deadline)) != INTERRUPTED)
                return next;
        }
        throw new InterruptedException();
    }

    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     *             before acquiring the lock
     */
    public long readLockInterruptibly() throws InterruptedException {
        long next;
        if (!Thread.interrupted() &&
                (next = acquireRead(true, 0L)) != INTERRUPTED)
            return next;
        throw new InterruptedException();
    }

    /**
     * Returns a stamp that can later be validated, or zero
     * if exclusively locked.
     *
     * @return a stamp, or zero if exclusively locked
     */
    // 乐观读
    public long tryOptimisticRead() {
        // 如果没有写锁，就返回state的高25位，这里把写所在位置一起返回了，是为了后面检测数据有没有被写过。
        long s;
        return (((s = state) & WBIT) == 0L) ? (s & SBITS) : 0L;
    }

    /**
     * Returns true if the lock has not been exclusively acquired
     * since issuance of the given stamp. Always returns false if the
     * stamp is zero. Always returns true if the stamp represents a
     * currently held lock. Invoking this method with a value not
     * obtained from {@link #tryOptimisticRead} or a locking method
     * for this lock has no defined effect or result.
     *
     * @param stamp a stamp
     * @return {@code true} if the lock has not been exclusively acquired
     *         since issuance of the given stamp; else false
     */
    // 检测乐观读版本号是否变化
    public boolean validate(long stamp) {
        // 强制加入内存屏障，刷新数据
        U.loadFence();
        // 检测两者的版本号是否一致，与SBITS与操作保证不受读操作的影响
        return (stamp & SBITS) == (state & SBITS);
    }

    /**
     * If the lock state matches the given stamp, releases the
     * exclusive lock.
     *
     * @param stamp a stamp returned by a write-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     *             not match the current state of this lock
     */
    public void unlockWrite(long stamp) {
        WNode h;
        // 检查版本号对不对
        if (state != stamp || (stamp & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        // 这行代码实际上有2个作用
        // 1. 更新版本号+1
        // 2. 释放写锁
        // stamp + WBIT实际上会把state的第8位置为0，也就相当于释放了写锁
        // 同时会进1，也就是高24位整体加1了
        state = (stamp += WBIT) == 0L ? ORIGIN : stamp;
        // 如果头节点不为空，并且状态不为0，调用release方法唤醒它的下一个节点
        if ((h = whead) != null && h.status != 0)
            release(h);
    }

    /**
     * If the lock state matches the given stamp, releases the
     * non-exclusive lock.
     *
     * @param stamp a stamp returned by a read-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     *             not match the current state of this lock
     */
    public void unlockRead(long stamp) {
        long s, m;
        WNode h;
        for (;;) {
            // 检查版本号
            if (((s = state) & SBITS) != (stamp & SBITS) ||
                    (stamp & ABITS) == 0L || (m = s & ABITS) == 0L || m == WBIT)
                throw new IllegalMonitorStateException();
            // 读线程个数正常
            if (m < RFULL) {
                // 释放一次读锁
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    // 如果读锁全部都释放了，且头节点不为空且状态不为0，唤醒它的下一个节点
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    break;
                }
            } else if (tryDecReaderOverflow(s) != 0L)
                // 读线程个数溢出检测
                break;
        }
    }

    /**
     * If the lock state matches the given stamp, releases the
     * corresponding mode of the lock.
     *
     * @param stamp a stamp returned by a lock operation
     * @throws IllegalMonitorStateException if the stamp does
     *             not match the current state of this lock
     */
    public void unlock(long stamp) {
        long a = stamp & ABITS, m, s;
        WNode h;
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L)
                break;
            else if (m == WBIT) {
                if (a != m)
                    break;
                state = (s += WBIT) == 0L ? ORIGIN : s;
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return;
            } else if (a == 0L || a >= WBIT)
                break;
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return;
                }
            } else if (tryDecReaderOverflow(s) != 0L)
                return;
        }
        throw new IllegalMonitorStateException();
    }

    /**
     * If the lock state matches the given stamp, performs one of
     * the following actions. If the stamp represents holding a write
     * lock, returns it. Or, if a read lock, if the write lock is
     * available, releases the read lock and returns a write stamp.
     * Or, if an optimistic read, returns a write stamp only if
     * immediately available. This method returns zero in all other
     * cases.
     *
     * @param stamp a stamp
     * @return a valid write stamp, or zero on failure
     */
    public long tryConvertToWriteLock(long stamp) {
        long a = stamp & ABITS, m, s, next;
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L) {
                if (a != 0L)
                    break;
                if (U.compareAndSwapLong(this, STATE, s, next = s + WBIT))
                    return next;
            } else if (m == WBIT) {
                if (a != m)
                    break;
                return stamp;
            } else if (m == RUNIT && a != 0L) {
                if (U.compareAndSwapLong(this, STATE, s,
                        next = s - RUNIT + WBIT))
                    return next;
            } else
                break;
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp, performs one of
     * the following actions. If the stamp represents holding a write
     * lock, releases it and obtains a read lock. Or, if a read lock,
     * returns it. Or, if an optimistic read, acquires a read lock and
     * returns a read stamp only if immediately available. This method
     * returns zero in all other cases.
     *
     * @param stamp a stamp
     * @return a valid read stamp, or zero on failure
     */
    public long tryConvertToReadLock(long stamp) {
        long a = stamp & ABITS, m, s, next;
        WNode h;
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L) {
                if (a != 0L)
                    break;
                else if (m < RFULL) {
                    if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                        return next;
                } else if ((next = tryIncReaderOverflow(s)) != 0L)
                    return next;
            } else if (m == WBIT) {
                if (a != m)
                    break;
                state = next = s + (WBIT + RUNIT);
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return next;
            } else if (a != 0L && a < WBIT)
                return stamp;
            else
                break;
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp then, if the stamp
     * represents holding a lock, releases it and returns an
     * observation stamp. Or, if an optimistic read, returns it if
     * validated. This method returns zero in all other cases, and so
     * may be useful as a form of "tryUnlock".
     *
     * @param stamp a stamp
     * @return a valid optimistic read stamp, or zero on failure
     */
    public long tryConvertToOptimisticRead(long stamp) {
        long a = stamp & ABITS, m, s, next;
        WNode h;
        U.loadFence();
        for (;;) {
            if (((s = state) & SBITS) != (stamp & SBITS))
                break;
            if ((m = s & ABITS) == 0L) {
                if (a != 0L)
                    break;
                return s;
            } else if (m == WBIT) {
                if (a != m)
                    break;
                state = next = (s += WBIT) == 0L ? ORIGIN : s;
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return next;
            } else if (a == 0L || a >= WBIT)
                break;
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, next = s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return next & SBITS;
                }
            } else if ((next = tryDecReaderOverflow(s)) != 0L)
                return next & SBITS;
        }
        return 0L;
    }

    /**
     * Releases the write lock if it is held, without requiring a
     * stamp value. This method may be useful for recovery after
     * errors.
     *
     * @return {@code true} if the lock was held, else false
     */
    public boolean tryUnlockWrite() {
        long s;
        WNode h;
        if (((s = state) & WBIT) != 0L) {
            state = (s += WBIT) == 0L ? ORIGIN : s;
            if ((h = whead) != null && h.status != 0)
                release(h);
            return true;
        }
        return false;
    }

    /**
     * Releases one hold of the read lock if it is held, without
     * requiring a stamp value. This method may be useful for recovery
     * after errors.
     *
     * @return {@code true} if the read lock was held, else false
     */
    public boolean tryUnlockRead() {
        long s, m;
        WNode h;
        while ((m = (s = state) & ABITS) != 0L && m < WBIT) {
            if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return true;
                }
            } else if (tryDecReaderOverflow(s) != 0L)
                return true;
        }
        return false;
    }

    // status monitoring methods

    /**
     * Returns combined state-held and overflow read count for given
     * state s.
     */
    private int getReadLockCount(long s) {
        long readers;
        if ((readers = s & RBITS) >= RFULL)
            readers = RFULL + readerOverflow;
        return (int) readers;
    }

    /**
     * Returns {@code true} if the lock is currently held exclusively.
     *
     * @return {@code true} if the lock is currently held exclusively
     */
    public boolean isWriteLocked() {
        return (state & WBIT) != 0L;
    }

    /**
     * Returns {@code true} if the lock is currently held non-exclusively.
     *
     * @return {@code true} if the lock is currently held non-exclusively
     */
    public boolean isReadLocked() {
        return (state & RBITS) != 0L;
    }

    /**
     * Queries the number of read locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     * 
     * @return the number of read locks held
     */
    public int getReadLockCount() {
        return getReadLockCount(state);
    }

    /**
     * Returns a string identifying this lock, as well as its lock
     * state. The state, in brackets, includes the String {@code
     * "Unlocked"} or the String {@code "Write-locked"} or the String
     * {@code "Read-locks:"} followed by the current number of
     * read-locks held.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        long s = state;
        return super.toString() +
                ((s & ABITS) == 0L
                        ? "[Unlocked]"
                        : (s & WBIT) != 0L ? "[Write-locked]" : "[Read-locks:" + getReadLockCount(s) + "]");
    }

    // views

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #readLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link
     * Lock#newCondition()} throws {@code
     * UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asReadLock() {
        ReadLockView v;
        return ((v = readLockView) != null ? v : (readLockView = new ReadLockView()));
    }

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #writeLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link
     * Lock#newCondition()} throws {@code
     * UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asWriteLock() {
        WriteLockView v;
        return ((v = writeLockView) != null ? v : (writeLockView = new WriteLockView()));
    }

    /**
     * Returns a {@link ReadWriteLock} view of this StampedLock in
     * which the {@link ReadWriteLock#readLock()} method is mapped to
     * {@link #asReadLock()}, and {@link ReadWriteLock#writeLock()} to
     * {@link #asWriteLock()}.
     *
     * @return the lock
     */
    public ReadWriteLock asReadWriteLock() {
        ReadWriteLockView v;
        return ((v = readWriteLockView) != null ? v : (readWriteLockView = new ReadWriteLockView()));
    }

    // view classes

    final class ReadLockView implements Lock {
        public void lock() {
            readLock();
        }

        public void lockInterruptibly() throws InterruptedException {
            readLockInterruptibly();
        }

        public boolean tryLock() {
            return tryReadLock() != 0L;
        }

        public boolean tryLock(long time, TimeUnit unit)
                throws InterruptedException {
            return tryReadLock(time, unit) != 0L;
        }

        public void unlock() {
            unstampedUnlockRead();
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    final class WriteLockView implements Lock {
        public void lock() {
            writeLock();
        }

        public void lockInterruptibly() throws InterruptedException {
            writeLockInterruptibly();
        }

        public boolean tryLock() {
            return tryWriteLock() != 0L;
        }

        public boolean tryLock(long time, TimeUnit unit)
                throws InterruptedException {
            return tryWriteLock(time, unit) != 0L;
        }

        public void unlock() {
            unstampedUnlockWrite();
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    final class ReadWriteLockView implements ReadWriteLock {
        public Lock readLock() {
            return asReadLock();
        }

        public Lock writeLock() {
            return asWriteLock();
        }
    }

    // Unlock methods without stamp argument checks for view classes.
    // Needed because view-class lock methods throw away stamps.

    final void unstampedUnlockWrite() {
        WNode h;
        long s;
        if (((s = state) & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        state = (s += WBIT) == 0L ? ORIGIN : s;
        if ((h = whead) != null && h.status != 0)
            release(h);
    }

    final void unstampedUnlockRead() {
        for (;;) {
            long s, m;
            WNode h;
            if ((m = (s = state) & ABITS) == 0L || m >= WBIT)
                throw new IllegalMonitorStateException();
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    break;
                }
            } else if (tryDecReaderOverflow(s) != 0L)
                break;
        }
    }

    private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        state = ORIGIN; // reset to unlocked state
    }

    // internals

    /**
     * Tries to increment readerOverflow by first setting state
     * access bits value to RBITS, indicating hold of spinlock,
     * then updating, then releasing.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryIncReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        // 这里如果是刚到达读锁最大阈值，则还可以进行加锁，有预留位
        if ((s & ABITS) == RFULL) {
            if (U.compareAndSwapLong(this, STATE, s, s | RBITS)) {
                ++readerOverflow;
                state = s;
                return s;
            }
            // 这里获取一个随机数和7也就是等待溢出的周期做&，如果为0，那么调用当前线程的yield方法
        } else if ((LockSupport.nextSecondarySeed() &
                OVERFLOW_YIELD_RATE) == 0)
            // 短暂释放当前线程的时间片
            Thread.yield();
        return 0L;
    }

    /**
     * Tries to decrement readerOverflow.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryDecReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        if ((s & ABITS) == RFULL) {
            if (U.compareAndSwapLong(this, STATE, s, s | RBITS)) {
                int r;
                long next;
                if ((r = readerOverflow) > 0) {
                    readerOverflow = r - 1;
                    next = s;
                } else
                    next = s - RUNIT;
                state = next;
                return next;
            }
        } else if ((LockSupport.nextSecondarySeed() &
                OVERFLOW_YIELD_RATE) == 0)
            Thread.yield();
        return 0L;
    }

    /**
     * Wakes up the successor of h (normally whead). This is normally
     * just h.next, but may require traversal from wtail if next
     * pointers are lagging. This may fail to wake up an acquiring
     * thread when one or more have been cancelled, but the cancel
     * methods themselves provide extra safeguards to ensure liveness.
     */
    private void release(WNode h) {
        if (h != null) {
            WNode q;
            Thread w;
            // 将其状态改为0
            U.compareAndSwapInt(h, WSTATUS, WAITING, 0);
            // 如果头节点的下一个节点为空，或者状态为已取消
            if ((q = h.next) == null || q.status == CANCELLED) {
                // 从尾节点向前遍历找到一个可用的节点
                for (WNode t = wtail; t != null && t != h; t = t.prev)
                    if (t.status <= 0)
                        q = t;
            }
            // 唤醒q节点所在的线程
            if (q != null && (w = q.thread) != null)
                U.unpark(w);
        }
    }

    /**
     * See above for explanation.
     *
     * @param interruptible true if should check interrupts and if so
     *            return INTERRUPTED
     * @param deadline if nonzero, the System.nanoTime value to timeout
     *            at (and return zero)
     * @return next state, or INTERRUPTED
     */
    /**
     * 这里对acquireWrite()方法做一个总结，这个方法里面有三段自旋逻辑：
     *
     * 第一段自旋——入队：
     *
     * （1）如果头节点等于尾节点，说明没有其它线程排队，那就多自旋一会，看能不能尝试获取到写锁；
     *
     * （2）否则，自旋次数为0，直接让其入队；
     *
     * 第二段自旋——阻塞并等待被唤醒 + 第三段自旋——不断尝试获取写锁：
     *
     * （1）第三段自旋在第二段自旋内部；
     *
     * （2）如果头节点等于前置节点，那就进入第三段自旋，不断尝试获取写锁；
     *
     * （3）否则，尝试唤醒头节点中等待着的读线程；
     *
     * （4）最后，如果当前线程一直都没有获取到写锁，就阻塞当前线程并等待被唤醒；
     * 
     * @param interruptible
     * @param deadline
     * @return
     */
    private long acquireWrite(boolean interruptible, long deadline) {
        // node为新增节点，p为尾节点(即将成为node的前置节点)
        WNode node = null, p;
        // 第一次自旋--入队
        for (int spins = -1;;) { // spin while enqueuing
            long m, s, ns;
            if ((m = (s = state) & ABITS) == 0L) {
                if (U.compareAndSwapLong(this, STATE, s, ns = s + WBIT))
                    return ns;
            } else if (spins < 0)
                /**
                 * 如果自旋次数小于0，则计算自旋的次数
                 * 如果当前有写锁独占且队列无元素，说明快轮到自己了
                 * 就自旋就行了，如果自旋完了还没轮到自己才入队
                 * 则自旋次数为SPINS常量，否则自选次数为0
                 */
                spins = (m == WBIT && wtail == whead) ? SPINS : 0;
            else if (spins > 0) {
                // 当自旋次数大于0时，当前这次自旋随机减一次自旋次数
                if (LockSupport.nextSecondarySeed() >= 0)
                    --spins;
            } else if ((p = wtail) == null) { // initialize queue
                // 如果队列未初始化，则新建一个空节点，并初始化头节点和尾节点
                WNode hd = new WNode(WMODE, null);
                if (U.compareAndSwapObject(this, WHEAD, null, hd))
                    wtail = hd;
            } else if (node == null)
                // 如果新增节点没有初始化，则创建，并更新新增节点的前置节点为新的尾节点
                node = new WNode(WMODE, p);
            else if (node.prev != p)
                // 如果尾节点有变化，则更新新增节点的前置节点为新的尾节点
                node.prev = p;
            else if (U.compareAndSwapObject(this, WTAIL, p, node)) {
                // 尝试更新新增节点为新的尾节点成功，则推出循环
                p.next = node;
                break;
            }
        }
        // 第二次自旋--阻塞并等待唤醒
        for (int spins = -1;;) {
            // h为头节点，np为新增节点的前置节点，pp为前前置节点，ps为前置节点的状态
            WNode h, np, pp;
            int ps;
            // 如果头节点等于前置节点，说明快轮到自己了
            if ((h = whead) == p) {
                if (spins < 0)
                    // 初始化自旋次数,这里的自旋次数是根据cpu的核数来计算出来的
                    spins = HEAD_SPINS;
                else if (spins < MAX_HEAD_SPINS)
                    // 增加自旋次数
                    spins <<= 1;
                // 第三次自旋，不断尝试获取写锁
                for (int k = spins;;) { // spin at head
                    long s, ns;
                    if (((s = state) & ABITS) == 0L) {
                        if (U.compareAndSwapLong(this, STATE, s,
                                ns = s + WBIT)) {
                            // 尝试获取写锁成功，将node设置为新头节点并清除其前置节点(gc)
                            whead = node;
                            node.prev = null;
                            return ns;
                        }
                        // 随机立减自旋次数，当自旋次数减为0
                    } else if (LockSupport.nextSecondarySeed() >= 0 &&
                            --k <= 0)
                        break;
                }
            } else if (h != null) { // help release stale waiters
                // 这段代码很难进来，是用于协助唤醒读节点的
                // 我是这么调试进来的：
                // 起三个写线程，两个读线程
                // 写线程1获取锁不要释放
                // 读线程1获取锁，读线程2获取锁（会阻塞）
                // 写线程2获取锁（会阻塞）
                // 写线程1释放锁，此时会唤醒读线程1
                // 在读线程1里面先不要唤醒读线程2
                // 写线程3获取锁，此时就会走到这里来了
                WNode c;
                Thread w;
                while ((c = h.cowait) != null) {
                    // 如果头节点的cowait链表(栈)不为空，唤醒里面的所有节点
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                            (w = c.thread) != null)
                        U.unpark(w);
                }
            }
            // 如果头节点没有变化
            if (whead == h) {
                // 如果尾节点有变化，则更新
                if ((np = node.prev) != p) {
                    if (np != null)
                        (p = np).next = node; // stale
                } else if ((ps = p.status) == 0)
                    // 如果尾节点状态为0，则更新成WAITING
                    U.compareAndSwapInt(p, WSTATUS, 0, WAITING);
                else if (ps == CANCELLED) {
                    // 如果尾节点状态为取消，则把它从链表中删除
                    if ((pp = p.prev) != null) {
                        node.prev = pp;
                        pp.next = node;
                    }
                } else {
                    // 有超时时间的处理
                    long time; // 0 argument to park means no timeout
                    if (deadline == 0L)
                        time = 0L;
                    else if ((time = deadline - System.nanoTime()) <= 0L)
                        // 已超时，删除当前节点
                        return cancelWaiter(node, node, false);
                    // 当前线程
                    Thread wt = Thread.currentThread();
                    U.putObject(wt, PARKBLOCKER, this);
                    // 把node线程的指向当前线程
                    node.thread = wt;
                    if (p.status < 0 && (p != h || (state & ABITS) != 0L) &&
                            whead == h && node.prev == p)
                        // 阻塞当前线程
                        U.park(false, time); // emulate LockSupport.park
                    // 当前节点被唤醒后，清除线程
                    node.thread = null;
                    U.putObject(wt, PARKBLOCKER, null);
                    // 如果中断了，取消当前节点
                    if (interruptible && Thread.interrupted())
                        return cancelWaiter(node, node, true);
                }
            }
        }
    }

    /**
     * See above for explanation.
     *
     * @param interruptible true if should check interrupts and if so
     *            return INTERRUPTED
     * @param deadline if nonzero, the System.nanoTime value to timeout
     *            at (and return zero)
     * @return next state, or INTERRUPTED
     */
    /**
     * （1）读节点进来都是先判断是头节点如果等于尾节点，说明快轮到自己了，就不断地尝试获取读锁，如果成功了就返回；
     *
     * （2）如果头节点不等于尾节点，这里就会让当前节点入队，这里入队又分成了两种；
     *
     * （3）一种是首个读节点入队，它是会排队到整个队列的尾部，然后跳出第一段自旋；
     *
     * （4）另一种是非第一个读节点入队，它是进入到首个读节点的cowait栈中，所以更确切地说应该是入栈；
     *
     * （5）不管是入队还入栈后，都会再次检测头节点是不是等于尾节点了，如果相等，则会再次不断尝试获取读锁；
     *
     * （6）如果头节点不等于尾节点，那么才会真正地阻塞当前线程并等待被唤醒；
     *
     * （7）上面说的首个读节点其实是连续的读线程中的首个，如果是两个读线程中间夹了一个写线程，还是老老实实的排队。
     * 
     * @param interruptible
     * @param deadline
     * @return
     */
    private long acquireRead(boolean interruptible, long deadline) {
        // node为新增节点，p为尾节点
        WNode node = null, p;
        // 循环次数从-1开始,第一段自旋--入队
        for (int spins = -1;;) {
            // 头节点
            WNode h;
            // 如果头节点等于尾节点，说明没有排队的县城，快轮到自己了，直接自旋不断尝试获取读锁
            if ((h = whead) == (p = wtail)) {
                // 第2段自旋--不断尝试获取读锁
                for (long m, s, ns;;) {
                    // 继续尝试获取读锁,如果成功了直接返回版本号
                    if ((m = (s = state) & ABITS) < RFULL ? U.compareAndSwapLong(this, STATE, s, ns = s + RUNIT) :
                    // 这里说明已经发生溢出了，读锁的数量到了最大值,这里调用tryIncReaderOverflow尝试继续累加
                            (m < WBIT && (ns = tryIncReaderOverflow(s)) != 0L))
                        return ns;
                    else if (m >= WBIT) {
                        // m>=WBIT 表示有其他线程先一步获取到了写锁
                        if (spins > 0) {
                            // 随机立减自旋次数
                            if (LockSupport.nextSecondarySeed() >= 0)
                                --spins;
                        } else {
                            // 如果自旋次数为0了，看看是否要跳出循环
                            if (spins == 0) {
                                WNode nh = whead, np = wtail;
                                if ((nh == h && np == p) || (h = nh) != (p = np))
                                    break;
                            }
                            // 设置自旋次数
                            spins = SPINS;
                        }
                    }
                }
            }
            // 如果尾节点为空，初始化头节点和尾节点
            if (p == null) { // initialize queue
                WNode hd = new WNode(WMODE, null);
                if (U.compareAndSwapObject(this, WHEAD, null, hd))
                    wtail = hd;
            } else if (node == null)
                // 如果新增节点为空，则进行初始化
                node = new WNode(RMODE, p);
            else if (h == p || p.mode != RMODE) {
                // 如果头节点等于尾节点或者尾节点不是读模式，则当前节点入队
                if (node.prev != p)
                    node.prev = p;
                else if (U.compareAndSwapObject(this, WTAIL, p, node)) {
                    p.next = node;
                    break;
                }
            } else if (!U.compareAndSwapObject(p, WCOWAIT,
                    node.cowait = p.cowait, node))
                // 接着上一个elseif，这里肯定是尾节点为读模式了
                // 将当前节点加入到尾节点的cowait中，这是一个栈
                // 上面的CAS成功了是不会进入到这里来的
                node.cowait = null;
            else {
                // 第三段自旋--阻塞当前线程并等待被唤醒
                for (;;) {
                    WNode pp, c;
                    Thread w;
                    // 如果头节点不为空且其cowait不为空，协助唤醒其中等待的读县城
                    if ((h = whead) != null && (c = h.cowait) != null &&
                            U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                            (w = c.thread) != null) // help release
                        U.unpark(w);
                    // 如果头节点等待前前置节点或者等于前置节点或者前置节点为空，这同样说明快轮到自己了
                    if (h == (pp = p.prev) || h == p || pp == null) {
                        long m, s, ns;
                        // 第4段自旋----同样是不断尝试获取锁
                        do {
                            if ((m = (s = state) & ABITS) < RFULL
                                    ? U.compareAndSwapLong(this, STATE, s,
                                            ns = s + RUNIT)
                                    : (m < WBIT &&
                                            (ns = tryIncReaderOverflow(s)) != 0L))
                                return ns;
                            // 只有当前时刻没有其他线程占有写锁就不断尝试
                        } while (m < WBIT);
                    }
                    // 如果头节点未曾改变且前前节点也未曾改变，阻塞当前线程
                    if (whead == h && p.prev == pp) {
                        long time;
                        // 如果前前置节点为空，或者头节点等于前置节点，或者前置节点已取消
                        // 从第一个for自旋开始重试
                        if (pp == null || h == p || p.status > 0) {
                            node = null; // throw away
                            break;
                        }
                        // 超时检测
                        if (deadline == 0L)
                            time = 0L;
                        else if ((time = deadline - System.nanoTime()) <= 0L)
                            // 如果超时了，取消当前节点
                            return cancelWaiter(node, p, false);
                        // 当前线程
                        Thread wt = Thread.currentThread();
                        U.putObject(wt, PARKBLOCKER, this);
                        // 设置进node中
                        node.thread = wt;
                        // 检测之前的条件未曾改变
                        if ((h != pp || (state & ABITS) == WBIT) &&
                                whead == h && p.prev == pp)
                            // 阻塞当前线程并等待被唤醒
                            U.park(false, time);
                        // 唤醒之后清理线程
                        node.thread = null;
                        U.putObject(wt, PARKBLOCKER, null);
                        // 如果中断了，则取消当前节点
                        if (interruptible && Thread.interrupted())
                            return cancelWaiter(node, p, true);
                    }
                }
            }
        }
        // 只有一个读线程会走到下面的for循环处，参考上面第一段自旋中又一个break，当第一个读线程入队时break出来的
        // 第五段自旋--跟上面的逻辑差不多，只不过是单独搞一个自旋针对第一个读线程
        for (int spins = -1;;) {
            WNode h, np, pp;
            int ps;
            // 如果头节点等于尾节点，说明快轮到自己了
            // 不断尝试获取锁
            if ((h = whead) == p) {
                // 设置自旋次数
                if (spins < 0)
                    spins = HEAD_SPINS;
                else if (spins < MAX_HEAD_SPINS)
                    spins <<= 1;
                // 第六段自旋--不断尝试获取读锁
                for (int k = spins;;) { // spin at head
                    long m, s, ns;
                    // 不断尝试获取读锁
                    if ((m = (s = state) & ABITS) < RFULL
                            ? U.compareAndSwapLong(this, STATE, s, ns = s + RUNIT)
                            : (m < WBIT && (ns = tryIncReaderOverflow(s)) != 0L)) {
                        // 获取到了读锁
                        WNode c;
                        Thread w;
                        whead = node;
                        node.prev = null;
                        // 唤醒当前节点中所有等待的读线程
                        // 因为当前节点是第一个读节点，所以它是在队列中的，其他读节点都是挂这个节点的cowait栈中的。
                        while ((c = node.cowait) != null) {
                            if (U.compareAndSwapObject(node, WCOWAIT,
                                    c, c.cowait) &&
                                    (w = c.thread) != null)
                                U.unpark(w);
                        }
                        // 返回版本号
                        return ns;
                        // 如果当前有其他线程占有着写锁，并且没有自旋次数了，就退出当前循环
                    } else if (m >= WBIT &&
                            LockSupport.nextSecondarySeed() >= 0 && --k <= 0)
                        break;
                }
            } else if (h != null) {
                // 如果头节点不等待尾节点且不为空且其为读模式，协助唤醒里面的读线程
                WNode c;
                Thread w;
                while ((c = h.cowait) != null) {
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                            (w = c.thread) != null)
                        U.unpark(w);
                }
            }
            // 如果头节点未曾发生变化
            if (whead == h) {
                // 更新前置节点及其等待状态
                if ((np = node.prev) != p) {
                    if (np != null)
                        (p = np).next = node; // stale
                } else if ((ps = p.status) == 0)
                    U.compareAndSwapInt(p, WSTATUS, 0, WAITING);
                else if (ps == CANCELLED) {
                    if ((pp = p.prev) != null) {
                        node.prev = pp;
                        pp.next = node;
                    }
                } else {
                    // 第一个读节点即将进入阻塞
                    long time;
                    // 超时设置
                    if (deadline == 0L)
                        time = 0L;
                    else if ((time = deadline - System.nanoTime()) <= 0L)
                        // 如果超时了取消当前节点
                        return cancelWaiter(node, node, false);
                    Thread wt = Thread.currentThread();
                    U.putObject(wt, PARKBLOCKER, this);
                    node.thread = wt;
                    if (p.status < 0 &&
                            (p != h || (state & ABITS) == WBIT) &&
                            whead == h && node.prev == p)
                        // 阻塞第一个读节点并等待被唤醒
                        U.park(false, time);
                    node.thread = null;
                    U.putObject(wt, PARKBLOCKER, null);
                    if (interruptible && Thread.interrupted())
                        return cancelWaiter(node, node, true);
                }
            }
        }
    }

    /**
     * If node non-null, forces cancel status and unsplices it from
     * queue if possible and wakes up any cowaiters (of the node, or
     * group, as applicable), and in any case helps release current
     * first waiter if lock is free. (Calling with null arguments
     * serves as a conditional form of release, which is not currently
     * needed but may be needed under possible future cancellation
     * policies). This is a variant of cancellation methods in
     * AbstractQueuedSynchronizer (see its detailed explanation in AQS
     * internal documentation).
     *
     * @param node if nonnull, the waiter
     * @param group either node or the group node is cowaiting with
     * @param interrupted if already interrupted
     * @return INTERRUPTED if interrupted or Thread.interrupted, else zero
     */
    private long cancelWaiter(WNode node, WNode group, boolean interrupted) {
        if (node != null && group != null) {
            Thread w;
            node.status = CANCELLED;
            // unsplice cancelled nodes from group
            for (WNode p = group, q; (q = p.cowait) != null;) {
                if (q.status == CANCELLED) {
                    U.compareAndSwapObject(p, WCOWAIT, q, q.cowait);
                    p = group; // restart
                } else
                    p = q;
            }
            if (group == node) {
                for (WNode r = group.cowait; r != null; r = r.cowait) {
                    if ((w = r.thread) != null)
                        U.unpark(w); // wake up uncancelled co-waiters
                }
                for (WNode pred = node.prev; pred != null;) { // unsplice
                    WNode succ, pp; // find valid successor
                    while ((succ = node.next) == null ||
                            succ.status == CANCELLED) {
                        WNode q = null; // find successor the slow way
                        for (WNode t = wtail; t != null && t != node; t = t.prev)
                            if (t.status != CANCELLED)
                                q = t; // don't link if succ cancelled
                        if (succ == q || // ensure accurate successor
                                U.compareAndSwapObject(node, WNEXT,
                                        succ, succ = q)) {
                            if (succ == null && node == wtail)
                                U.compareAndSwapObject(this, WTAIL, node, pred);
                            break;
                        }
                    }
                    if (pred.next == node) // unsplice pred link
                        U.compareAndSwapObject(pred, WNEXT, node, succ);
                    if (succ != null && (w = succ.thread) != null) {
                        succ.thread = null;
                        U.unpark(w); // wake up succ to observe new pred
                    }
                    if (pred.status != CANCELLED || (pp = pred.prev) == null)
                        break;
                    node.prev = pp; // repeat if new pred wrong/cancelled
                    U.compareAndSwapObject(pp, WNEXT, pred, succ);
                    pred = pp;
                }
            }
        }
        WNode h; // Possibly release first waiter
        while ((h = whead) != null) {
            long s;
            WNode q; // similar to release() but check eligibility
            if ((q = h.next) == null || q.status == CANCELLED) {
                for (WNode t = wtail; t != null && t != h; t = t.prev)
                    if (t.status <= 0)
                        q = t;
            }
            if (h == whead) {
                if (q != null && h.status == 0 &&
                        ((s = state) & ABITS) != WBIT && // waiter is eligible
                        (s == 0L || q.mode == RMODE))
                    release(h);
                break;
            }
        }
        return (interrupted || Thread.interrupted()) ? INTERRUPTED : 0L;
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final long STATE;
    private static final long WHEAD;
    private static final long WTAIL;
    private static final long WNEXT;
    private static final long WSTATUS;
    private static final long WCOWAIT;
    private static final long PARKBLOCKER;

    static {
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = StampedLock.class;
            Class<?> wk = WNode.class;
            STATE = U.objectFieldOffset(k.getDeclaredField("state"));
            WHEAD = U.objectFieldOffset(k.getDeclaredField("whead"));
            WTAIL = U.objectFieldOffset(k.getDeclaredField("wtail"));
            WSTATUS = U.objectFieldOffset(wk.getDeclaredField("status"));
            WNEXT = U.objectFieldOffset(wk.getDeclaredField("next"));
            WCOWAIT = U.objectFieldOffset(wk.getDeclaredField("cowait"));
            Class<?> tk = Thread.class;
            PARKBLOCKER = U.objectFieldOffset(tk.getDeclaredField("parkBlocker"));

        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
