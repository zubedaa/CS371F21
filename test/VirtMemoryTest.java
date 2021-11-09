package vm;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.graalvm.compiler.nodeinfo.StructuralInput.Memory;
import org.hamcrest.MatcherAssert;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)

public class VirtMemoryTest {
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @Before
    public void setUpStreams() {
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @Test
    public void test1_OutOfRange() {
        Memory m = new VirtMemory();
        m.startup();
        m.write(0xFFFFFF, Byte.parseByte("-1"));
        MatcherAssert.assertThat(0, not(errContent.toString().length()));
        byte x = m.read(0xFFFFFF);
        MatcherAssert.assertThat(0, not(errContent.toString().length()));
        // Code review q1: what is the max legit address for m.write()??
        // 64K
        m.shutdown();
    }

    @After
    public void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    public void test2_SingleWrite() {
        Memory m = new VirtMemory();
        m.startup();
        m.write(0x8000, Byte.parseByte("-1")); // write it to somewhere way beyond 16K
        m.shutdown();
        // now the disk should have persisted your write, reboot
        m = new VirtMemory();
        m.startup();
        byte data = m.read(0x8000);
        m.shutdown();
        assertEquals(Byte.parseByte("-1"), data);
    }

    @Test
    public void test3_WriteBackToSameBlock() {
        // every 32 writes triggers a write-back to disk.
        Memory m = new VirtMemory();
        m.startup();
        for (int i = 0; i < 32; i++) {
            m.write(i, Byte.parseByte("-1"));
        }
        m.shutdown();
        int writeCount = m.getPhyMemory().writeCountDisk();
        int readCount = m.getPhyMemory().readCountDisk();
        assertEquals(1, writeCount);
        // Code review q2: why is there only 1 disk write?
        // because it is the same block
        assertEquals(1, readCount);
        // Code review q3: why is there only 1 disk read?
        // because there is only 1 write on this block
    }

    @Test
    public void test4_WriteBackToMultiBlocks() {
        // every 32 writes triggers a write-back to disk.
        Memory m = new VirtMemory();
        m.startup();
        for (int i = 0; i < 32; i++) {
            m.write(i * 64, Byte.parseByte("-1"));
        }

        m.shutdown();
        int writeCount = m.getPhyMemory().writeCountDisk();
        int readCount = m.getPhyMemory().readCountDisk();
        assertEquals(32, writeCount);
        // Code review q4: why are there 32 disk writes?
        // because we dirtied 32 blocks
        assertEquals(32, readCount);
        // Code review q5: why are there 32 disk read?
        // because there are 32 writes
    }

    // the following are more realistic workloads
    static final int TEST_SIZE = 64 * 1024;// 64K, test on max address space!

    static byte fce(int adr) {
        return (byte) ((adr * 5 + 6) % 256 - 128);
    }

    static byte fce2(int adr) {
        return (byte) ((adr * 7 + 5) % 256 - 128);
    }

    @Test
    public void test5_End2EndForward() {
        Memory m = new VirtMemory();
        m.startup();
        boolean result = true;
        for (int i = 0; i < TEST_SIZE; i++)
            m.write(i, fce(i));
        for (int i = 0; i < TEST_SIZE; i++)
            if (m.read(i) != fce(i))
                result = false;
        assertEquals(true, result);
        m.shutdown();
        assertEquals(2048, m.getPhyMemory().writeCountDisk());
        // Code review q6: why are there 2048 disk writes?
        // because we dirtied 2048 blocks
        assertEquals(2048, m.getPhyMemory().readCountDisk());
        // Code review q7: why are there 2048 disk reads?
        // because there is the same as the number of writes

    }

    @Test
    public void test6_End2EndBackward() {
        Memory m = new VirtMemory();
        m.startup();
        boolean result = true;
        for (int i = 0; i < TEST_SIZE; i++)
            m.write(i, fce(i));
        for (int i = TEST_SIZE - 1; i >= 0; i--)
            if (m.read(i) != fce(i))
                result = false;
        assertEquals(true, result);
        m.shutdown();
        assertEquals(2048, m.getPhyMemory().writeCountDisk());
        // Code review q8: why are there 2048 disk writes?
        // because the shutdown doesn't add anymore writes
        assertEquals(1792, m.getPhyMemory().readCountDisk());
        // Code review q9: why are there 1792 disk writes? Why is it different from
        // test5?
        // because "TEST_SIZE - 1"
        // the for loop for read in test5 is different

    }

    @Test
    public void test7_End2EndMix() {
        Memory m = new VirtMemory();
        m.startup();
        boolean result = true;
        for (int i = TEST_SIZE - 1; i >= 0; i--)
            m.write(i, fce(i));
        for (int posun = 0; posun < TEST_SIZE; posun += 100) {
            for (int i = 0; i < TEST_SIZE; i++) {
                int adr = (i + posun) % TEST_SIZE;
                if (m.read(adr) != fce(adr))
                    result = false;
            }
        }
        int posun_zapis = 55;
        for (int i = 0; i < TEST_SIZE; i++) {
            int adr = (i + posun_zapis) % TEST_SIZE;
            m.write(adr, fce2(adr));
        }
        for (int posun = 20; posun < TEST_SIZE; posun += 100) {
            for (int i = 0; i < TEST_SIZE; i++) {
                int adr = (i + posun) % TEST_SIZE;
                if (m.read(adr) != fce2(adr))
                    result = false;
            }
        }
        assertEquals(true, result);
        m.shutdown();
    }
}
