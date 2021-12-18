/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.atomic;

import java.io.Serializable;

/**
 * One or more variables that together maintain an initially zero
 * {@code long} sum. When updates (method {@link #add}) are contended
 * across threads, the set of variables may grow dynamically to reduce
 * contention. Method {@link #sum} (or, equivalently, {@link
 * #longValue}) returns the current total combined across the
 * variables maintaining the sum.
 *
 * <p>
 * This class is usually preferable to {@link AtomicLong} when
 * multiple threads update a common sum that is used for purposes such
 * as collecting statistics, not for fine-grained synchronization
 * control. Under low update contention, the two classes have similar
 * characteristics. But under high contention, expected throughput of
 * this class is significantly higher, at the expense of higher space
 * consumption.
 *
 * <p>
 * LongAdders can be used with a {@link
 * java.util.concurrent.ConcurrentHashMap} to maintain a scalable
 * frequency map (a form of histogram or multiset). For example, to
 * add a count to a {@code ConcurrentHashMap<String,LongAdder> freqs},
 * initializing if not already present, you can use {@code
 * freqs.computeIfAbsent(k -> new LongAdder()).increment();}
 *
 * <p>
 * This class extends {@link Number}, but does <em>not</em> define
 * methods such as {@code equals}, {@code hashCode} and {@code
 * compareTo} because instances are expected to be mutated, and so are
 * not useful as collection keys.
 *
 * @since 1.8
 * @author Doug Lea
 */


/**
 * 总结
 * （1）LongAdder通过base和cells数组来存储值；
 *
 * （2）不同的线程会hash到不同的cell上去更新，减少了竞争；
 *
 * （3）LongAdder的性能非常高，最终会达到一种无竞争的状态；
 *
 * 彩蛋
 * 在longAccumulate()方法中有个条件是n >= NCPU就不会走到扩容逻辑了，而n是2的倍数，那是不是代表cells数组最大只能达到大于等于NCPU的最小2次方？
 *
 * 答案是明确的。因为同一个CPU核心同时只会运行一个线程，而更新失败了说明有两个不同的核心更新了同一个Cell，这时会重新设置更新失败的那个线程的probe值，这样下一次它所在的Cell很大概率会发生改变，如果运行的时间足够长，最终会出现同一个核心的所有线程都会hash到同一个Cell（大概率，但不一定全在一个Cell上）上去更新，所以，这里cells数组中长度并不需要太长，达到CPU核心数足够了。
 *
 * 如果电脑是8核的，所以这里cells的数组最大只会到8，达到8就不会扩容了。
 */
public class LongAdder extends Striped64 implements Serializable {
    private static final long serialVersionUID = 7249069246863182397L;

    /**
     * Creates a new adder with initial sum of zero.
     */
    public LongAdder() {}

    /**
     * Adds the given value.
     *
     * @param x the value to add
     */
    /**
     * 最初无竞争或有其它线程在创建cells数组时使用base更新值，有过竞争时使用cells更新值。
     *
     * 最初无竞争是指一开始没有线程之间的竞争，但也有可能是多线程在操作，只是这些线程没有同时去更新base的值。
     *
     * 有过竞争是指只要出现过竞争不管后面有没有竞争都使用cells更新值，规则是不同的线程hash到不同的cell上去更新，减少竞争。
     * 
     * （1）最初无竞争时只更新base；
     *
     * （2）直到更新base失败时，创建cells数组；
     *
     * （3）当多个线程竞争同一个Cell比较激烈时，可能要扩容；
     * 
     * @param x
     */
    public void add(long x) {
        // as是Striped64中的cells属性
        // b是Striped64中的base属性
        // v是当前线程hash到的Cell中存储的值
        // m是cells的长度减1，hash时作为掩码使用
        // a是当前线程hash到的Cell
        Cell[] as;
        long b, v;
        int m;
        Cell a;
        // 条件1：cells不为空，说明出现过竞争，cells已经创建
        // 条件2：cas操作base失败，说明其它线程先一步修改了base，正在出现竞争
        if ((as = cells) != null || !casBase(b = base, b + x)) {
            // true表示当前竞争还不激烈
            // false表示竞争激烈，多个线程hash到同一个cell，可能要扩容
            boolean uncontended = true;
            // 条件1：cells为空，说明正在出现竞争，上面是从条件2过来的
            // 条件2：应该不会出现
            // 条件3：当前线程所在的Cell为空，说明当前线程还没有更新过Cell，应初始化一个Cell
            // 条件4：更新当前线程所在的Cell失败，说明现在竞争很激烈，多个线程hash到了同一个Cell，应扩容
            if (as == null || (m = as.length - 1) < 0 ||
            // getProbe()方法返回的是线程中的threadLocalRandomProbe字段
            // 它是通过随机数生成的一个值，对于一个确定的线程这个值是固定的
            // 除非刻意修改它
                    (a = as[getProbe() & m]) == null ||
                    !(uncontended = a.cas(v = a.value, v + x)))
                // 调用Striped64中的方法处理
                longAccumulate(x, null, uncontended);
        }
    }

    /**
     * Equivalent to {@code add(1)}.
     */
    public void increment() {
        add(1L);
    }

    /**
     * Equivalent to {@code add(-1)}.
     */
    public void decrement() {
        add(-1L);
    }

    /**
     * Returns the current sum. The returned value is <em>NOT</em> an
     * atomic snapshot; invocation in the absence of concurrent
     * updates returns an accurate result, but concurrent updates that
     * occur while the sum is being calculated might not be
     * incorporated.
     *
     * @return the sum
     */
    // sum()方法是获取LongAdder中真正存储的值的大小，通过把base和所有段相加得到。
    // 可以看到sum()方法是把base和所有段的值相加得到，那么，这里有一个问题，如果前面已经累加到sum上的Cell的value有修改，不是就没法计算到了么？
    // 答案确实如此，所以LongAdder可以说不是强一致性的，它是最终一致性的。
    public long sum() {
        Cell[] as = cells;
        Cell a;
        // sum初始化等于base
        long sum = base;
        // 如果cells不为空
        if (as != null) {
            // 遍历所有的Cell
            for (int i = 0; i < as.length; ++i) {
                // 如果所在的Cell不为空，就把它的value累加到sum中
                if ((a = as[i]) != null)
                    sum += a.value;
            }
        }
        // 返回sum
        return sum;
    }

    /**
     * Resets variables maintaining the sum to zero. This method may
     * be a useful alternative to creating a new adder, but is only
     * effective if there are no concurrent updates. Because this
     * method is intrinsically racy, it should only be used when it is
     * known that no threads are concurrently updating.
     */
    public void reset() {
        Cell[] as = cells;
        Cell a;
        base = 0L;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    a.value = 0L;
            }
        }
    }

    /**
     * Equivalent in effect to {@link #sum} followed by {@link
     * #reset}. This method may apply for example during quiescent
     * points between multithreaded computations. If there are
     * updates concurrent with this method, the returned value is
     * <em>not</em> guaranteed to be the final value occurring before
     * the reset.
     *
     * @return the sum
     */
    public long sumThenReset() {
        Cell[] as = cells;
        Cell a;
        long sum = base;
        base = 0L;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null) {
                    sum += a.value;
                    a.value = 0L;
                }
            }
        }
        return sum;
    }

    /**
     * Returns the String representation of the {@link #sum}.
     * 
     * @return the String representation of the {@link #sum}
     */
    public String toString() {
        return Long.toString(sum());
    }

    /**
     * Equivalent to {@link #sum}.
     *
     * @return the sum
     */
    public long longValue() {
        return sum();
    }

    /**
     * Returns the {@link #sum} as an {@code int} after a narrowing
     * primitive conversion.
     */
    public int intValue() {
        return (int) sum();
    }

    /**
     * Returns the {@link #sum} as a {@code float}
     * after a widening primitive conversion.
     */
    public float floatValue() {
        return (float) sum();
    }

    /**
     * Returns the {@link #sum} as a {@code double} after a widening
     * primitive conversion.
     */
    public double doubleValue() {
        return (double) sum();
    }

    /**
     * Serialization proxy, used to avoid reference to the non-public
     * Striped64 superclass in serialized forms.
     * 
     * @serial include
     */
    private static class SerializationProxy implements Serializable {
        private static final long serialVersionUID = 7249069246863182397L;

        /**
         * The current value returned by sum().
         * 
         * @serial
         */
        private final long value;

        SerializationProxy(LongAdder a) {
            value = a.sum();
        }

        /**
         * Return a {@code LongAdder} object with initial state
         * held by this proxy.
         *
         * @return a {@code LongAdder} object with initial state
         *         held by this proxy.
         */
        private Object readResolve() {
            LongAdder a = new LongAdder();
            a.base = value;
            return a;
        }
    }

    /**
     * Returns a
     * <a href=
     * "../../../../serialized-form.html#java.util.concurrent.atomic.LongAdder.SerializationProxy">
     * SerializationProxy</a>
     * representing the state of this instance.
     *
     * @return a {@link SerializationProxy}
     *         representing the state of this instance
     */
    private Object writeReplace() {
        return new SerializationProxy(this);
    }

    /**
     * @param s the stream
     * @throws java.io.InvalidObjectException always
     */
    private void readObject(java.io.ObjectInputStream s)
            throws java.io.InvalidObjectException {
        throw new java.io.InvalidObjectException("Proxy required");
    }

}
