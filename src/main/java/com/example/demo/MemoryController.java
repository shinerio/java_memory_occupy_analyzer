package com.example.demo;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

@RestController
public class MemoryController {

    private Queue<HeapMemoryHolder> heapMemoryHolder = new ConcurrentLinkedDeque<>();
    private Queue<DirectByteBufferHolder> directByteBufferHolder = new ConcurrentLinkedDeque<>();
    private Queue<UnsafeMemoryHolder> unsafeMemoryHolder = new ConcurrentLinkedDeque<>();

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

    static class UnsafeMemoryHolder {
        private long address;
        private long size;
        public UnsafeMemoryHolder(long address, long size) {
            this.address = address;
            this.size = size;
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

    @RequestMapping("/heap/{size_in_mb}")
    public String allocateHeapMemory(@PathVariable("size_in_mb")int sizeInMB) {
        try {
            byte[] memory = new byte[sizeInMB * 1024 * 1024]; // Allocate size_in_mb MB
            // Optionally, you can fill the array to ensure memory is actually allocated
            // Fill with some data
            Arrays.fill(memory, (byte) 1);
            heapMemoryHolder.add(new HeapMemoryHolder(memory));
            return "Allocated " + sizeInMB + " MB of heap memory.";
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            return "Failed to allocate " + sizeInMB + " MB of heap memory: " + e.getMessage();
        }
    }

    @RequestMapping("/heap/release")
    public String releaseHeapMemory() {
        HeapMemoryHolder poll = heapMemoryHolder.poll();
        System.gc();
        return "Released " + poll.memory.length / 1024 / 1024 + " MB of heap memory.";
    }

    @RequestMapping("/direct_byte_buffer/{size_in_mb}")
    public String allocateDirectByteBuffer(@PathVariable("size_in_mb")int sizeInMB) {
        try {
            // Allocate direct byte buffer of size_in_mb MB
            ByteBuffer buffer = ByteBuffer.allocateDirect(sizeInMB * 1024 * 1024);
            directByteBufferHolder.add(new DirectByteBufferHolder(buffer));
            return "Allocated " + sizeInMB + " MB of direct byte buffer memory.";
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            return "Failed to allocate " + sizeInMB + " MB of direct byte buffer memory: " + e.getMessage();
        }
    }

    @RequestMapping("/direct_byte_buffer/release")
    public String releaseDirectByteBuffer() {
        directByteBufferHolder.poll();
        System.gc();
        return "Released direct byte buffer memory.";
    }

    @RequestMapping("/unsafe/{size_in_mb}")
    public String allocateUnsafe(@PathVariable("size_in_mb")int sizeInMB) {
        try {
            int bytes = sizeInMB * 1024 * 1024;
            long base = MemoryController.getUnsafe().allocateMemory(bytes);
            for (long i = 0; i < bytes; i += 8) {
                MemoryController.getUnsafe().putChar(base + i, 'a'); // 触发物理内存分配
            }
            unsafeMemoryHolder.add(new UnsafeMemoryHolder(base, sizeInMB));
            return "Allocated " + sizeInMB + " MB of unsafe memory.";
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            return "Failed to allocate " + sizeInMB + " MB of unsafe memory: " + e.getMessage();
        }
    }

    @RequestMapping("/unsafe/release")
    public String releaseUnsafe() {
        UnsafeMemoryHolder poll = unsafeMemoryHolder.poll();
        MemoryController.getUnsafe().freeMemory(poll.address);
        return "Released " + poll.size + " MB of unsafe memory.";
    }
}
