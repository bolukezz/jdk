package test;

import org.junit.Test;

import java.util.concurrent.locks.ReentrantLock;

/**
 * @author zhuyumeng
 * @date 2021/9/3 10:17 下午
 */
public class TestJDK {
    static final ReentrantLock lock = new ReentrantLock();
    static int count = 0;

    @Test
    void contextLoads() {
    }

    @Test
    void testLock01() {
        for (int i = 0; i < 10; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        lock.lock();
                        System.out.println(++count);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        lock.unlock();
                    }
                }
            }).start();
        }
    }
}
