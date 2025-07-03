package com.example.demo;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

@RestController
public class MemoryController {

    private Queue<HeapMemoryHolder> heapMemoryHolder = new ConcurrentLinkedDeque<>();
    private Queue<DirectByteBufferHolder> directByteBufferHolder = new ConcurrentLinkedDeque<>();
    private Queue<MemoryHolder> unsafeMemoryHolder = new ConcurrentLinkedDeque<>();
    private Queue<MemoryHolder> jniMemoryHolder = new ConcurrentLinkedDeque<>();
    private Queue<NettyMemoryHolder> nettyMemoryHolder = new ConcurrentLinkedDeque<>();

    static class HeapMemoryHolder {
        private byte[] memory;
        public HeapMemoryHolder(byte[] memory) {
            this.memory = memory;
        }
    }

    static class DirectByteBufferHolder {
        private ByteBuffer buffer;
        public DirectByteBufferHolder(ByteBuffer buffer) {
            this.buffer = buffer;
        }
    }

    static class MemoryHolder {
        private long address;
        private long size;
        public MemoryHolder(long address, long size) {
            this.address = address;
            this.size = size;
        }
    }

    static class NettyMemoryHolder {
        private ByteBuf buf;
        public NettyMemoryHolder(ByteBuf buf) {
            this.buf = buf;
        }
    }

    public static sun.misc.Unsafe getUnsafe() {
        Field theUnsafe;
        try {
            theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return (sun.misc.Unsafe) theUnsafe.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    // 工具方法：通过反射获取DirectByteBuffer或Netty ByteBuf的address字段
    public static long getBufferAddress(Object buffer) {
        try {
            java.lang.reflect.Field addressField = buffer.getClass().getDeclaredField("address");
            addressField.setAccessible(true);
            return addressField.getLong(buffer);
        } catch (Exception e) {
            return 0L;
        }
    }

    @RequestMapping("/heap/{size_in_mb}")
    public String allocateHeapMemory(@PathVariable("size_in_mb")int sizeInMB) {
        try {
            byte[] memory = new byte[sizeInMB * 1024 * 1024];
            Arrays.fill(memory, (byte) 'a');
            heapMemoryHolder.add(new HeapMemoryHolder(memory));
            int hash = System.identityHashCode(memory);
            int len = memory.length;
            return "Allocated " + sizeInMB + " MB of heap memory. array hash: 0x" + Integer.toHexString(hash) + ", length: " + len;
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            return "Failed to allocate " + sizeInMB + " MB of heap memory: " + e.getMessage();
        }
    }

    @RequestMapping("/heap/release")
    public String releaseHeapMemory() {
        HeapMemoryHolder poll = heapMemoryHolder.poll();
        System.gc();
        int hash = poll != null ? System.identityHashCode(poll.memory) : 0;
        int len = poll != null ? poll.memory.length : 0;
        return "Released heap memory. array hash: 0x" + Integer.toHexString(hash) + ", length: " + len;
    }

    @RequestMapping("/direct_byte_buffer/{size_in_mb}")
    public String allocateDirectByteBuffer(@PathVariable("size_in_mb")int sizeInMB) {
        try {
            ByteBuffer buffer = ByteBuffer.allocateDirect(sizeInMB * 1024 * 1024);
            directByteBufferHolder.add(new DirectByteBufferHolder(buffer));
            long address = getBufferAddress(buffer);
            int cap = buffer.capacity();
            return "Allocated " + sizeInMB + " MB of direct byte buffer memory at [0x" + Long.toHexString(address) + ", 0x" + Long.toHexString(address + cap) + ")";
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            return "Failed to allocate " + sizeInMB + " MB of direct byte buffer memory: " + e.getMessage();
        }
    }

    @RequestMapping("/direct_byte_buffer/release")
    public String releaseDirectByteBuffer() {
        DirectByteBufferHolder poll = directByteBufferHolder.poll();
        long address = poll != null ? getBufferAddress(poll.buffer) : 0L;
        int cap = poll != null ? poll.buffer.capacity() : 0;
        System.gc();
        return "Released direct byte buffer memory at [0x" + Long.toHexString(address) + ", 0x" + Long.toHexString(address + cap) + ")";
    }

    @RequestMapping("/unsafe/{size_in_mb}")
    public String allocateUnsafe(@PathVariable("size_in_mb")int sizeInMB) {
        try {
            int bytes = sizeInMB * 1024 * 1024;
            long base = MemoryController.getUnsafe().allocateMemory(bytes);
            for (long i = 0; i < bytes; i += 8) {
                MemoryController.getUnsafe().putChar(base + i, 'a');
            }
            unsafeMemoryHolder.add(new MemoryHolder(base, bytes));
            return "Allocated " + sizeInMB + " MB of unsafe memory at [0x" + Long.toHexString(base) + ", 0x" + Long.toHexString(base + bytes) + ")";
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            return "Failed to allocate " + sizeInMB + " MB of unsafe memory: " + e.getMessage();
        }
    }

    @RequestMapping("/unsafe/release")
    public String releaseUnsafe() {
        MemoryHolder poll = unsafeMemoryHolder.poll();
        long address = poll != null ? poll.address : 0L;
        long size = poll != null ? poll.size : 0L;
        MemoryController.getUnsafe().freeMemory(address);
        return "Released unsafe memory at [0x" + Long.toHexString(address) + ", 0x" + Long.toHexString(address + size) + ")";
    }

    @RequestMapping("/jni/{size_in_mb}")
    public String allocateJNI(@PathVariable("size_in_mb")int sizeInMB) {
        long bytes = sizeInMB * 1024L * 1024L;
        long address = NativeMemoryUtils.allocateAndFillMemory(bytes, "a");
        jniMemoryHolder.add(new MemoryHolder(address, bytes));
        return "Allocated " + sizeInMB + " MB of JNI memory at [0x" + Long.toHexString(address) + ", 0x" + Long.toHexString(address + bytes) + ")";
    }

    @RequestMapping("/jni/release")
    public String releaseJNI() {
        MemoryHolder poll = jniMemoryHolder.poll();
        long address = poll != null ? poll.address : 0L;
        long size = poll != null ? poll.size : 0L;
        NativeMemoryUtils.freeMemory(address);
        return "Released JNI memory at [0x" + Long.toHexString(address) + ", 0x" + Long.toHexString(address + size) + ")";
    }

    @RequestMapping("/netty/{size_in_mb}")
    public String allocateNettyMemory(@PathVariable("size_in_mb") int sizeInMB) {
        try {
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(sizeInMB * 1024 * 1024);
            buf.writeZero(sizeInMB * 1024 * 1024);
            nettyMemoryHolder.add(new NettyMemoryHolder(buf));
            ByteBuffer nioBuf = buf.nioBuffer();
            long address = getBufferAddress(nioBuf);
            int cap = buf.capacity();
            return "Allocated " + sizeInMB + " MB of Netty direct memory at [0x" + Long.toHexString(address) + ", 0x" + Long.toHexString(address + cap) + ")";
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            return "Failed to allocate " + sizeInMB + " MB of Netty direct memory: " + e.getMessage();
        }
    }

    @RequestMapping("/netty/release")
    public String releaseNettyMemory() {
        NettyMemoryHolder poll = nettyMemoryHolder.poll();
        ByteBuffer nioBuf = poll != null && poll.buf != null ? poll.buf.nioBuffer() : null;
        long address = nioBuf != null ? getBufferAddress(nioBuf) : 0L;
        int cap = poll != null && poll.buf != null ? poll.buf.capacity() : 0;
        if (poll != null && poll.buf != null) {
            poll.buf.release();
            return "Released Netty direct memory at [0x" + Long.toHexString(address) + ", 0x" + Long.toHexString(address + cap) + ")";
        }
        return "No Netty direct memory to release.";
    }
}
