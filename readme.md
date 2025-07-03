# 1. 总结
java应用的内存主要分为三块
1. jvm管理的堆内存
2. jvm管理的非堆内存
3. 非jvm管理的内存
## 1.1. JVM管理的堆内存（Heap Memory）
存储对象实例，是 Java 内存管理的核心区域，由所有线程共享。
-  **新生代（Young Generation）**：存放新创建的对象，包括 Eden 区和两个 Survivor 区（S0、S1），对象在此经历多次 GC 后若存活会晋升到老年代。
- **老年代（Old Generation）**：存储生命周期较长的对象，如长期持有的缓存对象。
## 1.2. JVM管理的非堆内存（Non-Heap Memory）
存储与JVM运行时环境相关的数据，不直接存放对象实例。
-  **元空间（Metaspace）**：逻辑上仍属堆外内存，但由JVM管理，存放类结构、方法字节码、静态变量等。
- **JVM 栈（JVM Stack）**：每个线程独有，存储栈帧（局部变量、方法参数、操作数栈等），栈深度过大会导致`StackOverflowError`。
- **本地方法栈（Native Method Stack）**：用于调用 Native 方法（如 JVM 底层 C/C++ 代码）的栈空间。
- **程序计数器（Program Counter Register）**：记录当前线程执行的字节码位置，是最小的内存区域。
## 1.3. 非JVM管理的内存（Off-Heap Memory）
由操作系统直接管理，JVM通过本地接口访问，不受JVM堆内存限制。
- **直接内存（Direct Memory）**：通过`java.nio.ByteBuffer.allocateDirect()`创建，用于NIO操作（如文件读写、网络通信），避免堆内存与本地内存的拷贝开销。
- **JNI本地内存**：通过JNI调用C/C++代码分配的内存，需手动释放（如使用malloc,free等），否则可能导致内存泄漏。
- **堆外缓存**：如Redis客户端、Netty框架的内存池，直接使用系统内存提高性能。
> [!warning]
非 JVM 管理的内存不受 GC 控制，若使用不当（如大量分配未释放），可能导致系统 OOM（Out of Memory）
## 1.4. 不同内存的观测方法
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507022332395.png)
> [!note]
> 通过Unsafe方法直接管理的内存也可以通过jcmd观测到
# 2. 实验
## 2.1. 测试应用
测试应用可以通过以下命令获取
```shell
git clone git@github.com:shinerio/java_memory_occupy_analyzer.git
```
可以通过以下命令启动
```shell
java -XX:NativeMemoryTracking=detail -Xms256m -Xmx256m -XX:MetaspaceSize=64m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=5,filesize=10M -jar demo-0.0.1-SNAPSHOT.jar
```
## 2.2. heap memory
### 2.2.1. 测试命令
```shell
curl localhost:8080/heap/128
curl localhost:8080/heap/release
```
### 2.2.2. OOM异常
```
java.lang.OutOfMemoryError: Java heap space
```
### 2.2.3. arthas观测
堆内存正常使用和释放
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507022307583.png)
### 2.2.4. jcmd观测
```
jcmd `ps -ef|grep demo|grep -v grep|awk '{print $2}'` VM.native_memory scale=MB
```
关键信息如下
```
 Total: reserved=894MB, committed=190MB
       malloc: 36MB #122322
       mmap:   reserved=858MB, committed=154MB

-                 Java Heap (reserved=256MB, committed=66MB)
                            (mmap: reserved=256MB, committed=66MB, at peak) 

# curl localhost:8080/heap/128

Total: reserved=879MB, committed=299MB
       malloc: 21MB #130291
       mmap:   reserved=858MB, committed=279MB

-                 Java Heap (reserved=256MB, committed=186MB)
                            (mmap: reserved=256MB, committed=186MB, at peak) 

# curl localhost:8080/heap/release

Total: reserved=878MB, committed=174MB
       malloc: 20MB #128567
       mmap:   reserved=858MB, committed=154MB

-                 Java Heap (reserved=256MB, committed=64MB)
                            (mmap: reserved=256MB, committed=64MB, peak=237MB) 
```
### 2.2.5. rss观测
- `-Xms` 参数用于指定 JVM 启动时**逻辑上分配的堆内存大小**，例如 `-Xms256m` 表示初始堆大小为 256MB。
- **操作系统不会立即为这些内存分配物理页面**（即 RAM 空间），而是在 JVM 需要实际使用内存时（例如对象创建）才逐步分配。
  JVM 采用 “延迟分配” 策略：
- 初始时，堆内存仅在逻辑上被预留（通过 `mmap` 系统调用分配地址空间），但物理内存（RSS）不会立即增长。
- 当对象被创建并填充数据时，操作系统才会为这些内存区域分配实际的物理页面，此时 RSS 才会逐渐增加。
> [!note]
一旦堆内存被使用后，即使后面JVM通过GC释放了堆内存，超过xms设定部分的内存也不会还给系统，体现在RSS值不会小于xms。
#### 2.2.5.1. xms < xmx
```
-Xms64m -Xmx256m
```
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507022305356.png)

- 执行`curl localhost:8080/heap/128`分配堆内存
- 执行`curl localhost:8080/heap/release`释放堆内存
  GC日志
```
[2025-07-02T23:04:07.843+0800][89.112s][info][gc             ] GC(9) Pause Full (System.gc()) 145M->12M(64M) 26.362ms
```
#### 2.2.5.2. xms = xmx
```
-Xms256m -Xmx256m
```
- 执行`curl localhost:8080/heap/128`分配堆内存
- 执行`curl localhost:8080/heap/release`释放堆内存
  ![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507022300670.png)
  GC日志
```
[2025-07-02T23:01:41.604+0800][147.067s][info][gc             ] GC(7) Pause Full (System.gc()) 143M->11M(256M) 25.176ms
```
## 2.3. ByteBuffer.allocateDirect
直接内存的大小默认与xmx值相当，可通过以下命令显示指定
```bash
-XX:MaxDirectMemorySize=128m
```
### 2.3.1. 命令
```shell
# 分配128M直接内存
curl localhost:8080/direct_byte_buffer/128
# 释放
curl localhost:8080/direct_byte_buffer/release
```
### 2.3.2. OOM
```
java.lang.OutOfMemoryError: Cannot reserve 134217728 bytes of direct buffer memory (allocated: 134252544, limit: 268435456)
```
### 2.3.3. arthas观测
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507022238405.png)
### 2.3.4. jcmd观测
分配前
```
-                 Java Heap (reserved=256MB, committed=64MB)
                            (mmap: reserved=256MB, committed=64MB, peak=66MB) 
                            
-                  Compiler (reserved=0MB, committed=0MB)
                            (arena=0MB #4) (peak=24MB #11)
 
-                  Internal (reserved=2MB, committed=2MB)
                            (malloc=2MB #6615) (at peak) 
```
分配后
```
-                 Java Heap (reserved=256MB, committed=64MB)
                            (mmap: reserved=256MB, committed=64MB, peak=66MB) 
                            
-                  Internal (reserved=2MB, committed=2MB)
                            (malloc=2MB #6627) (peak=2MB #6615) 
 
-                     Other (reserved=132MB, committed=132MB)
                            (malloc=132MB #24) (at peak) 
```
释放后
```
-                 Java Heap (reserved=256MB, committed=64MB)
                            (mmap: reserved=256MB, committed=64MB, peak=66MB) 
 
-                  Internal (reserved=3MB, committed=3MB)
                            (malloc=3MB #6812) (peak=3MB #6804) 
 
-                     Other (reserved=4MB, committed=4MB)
                            (malloc=4MB #26) (peak=132MB #26) 
```
### 2.3.5. rss观测
通过`ByteBuffer.allocateDirect`分配和释放的内存会立即体现在RSS的变化上。
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507022243125.png)
## 2.4. netty(PooledByteBufAllocator)
### 2.4.1. 命令
```shell
curl localhost:8080/netty/128
curl localhost:8080/netty/release
```
### 2.4.2. oom
```
java.lang.OutOfMemoryError: Cannot reserve 536870912 bytes of direct buffer memory (allocated: 4237314, limit: 268435456)
```
### 2.4.3. arthas观测
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507032250775.png)
### 2.4.4. jcmd观测
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507032253844.png)
### 2.4.5. rss观测
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507032255387.png)
## 2.5. unsafe
直接使用unsafe命令分配的内存不受`-XX:MaxDirectMemorySize`控制
### 2.5.1. OOM
连续执行`curl localhost:8080/unsafe/512`尝试分配内存，当超过系统物理内存上限后，进程会被直接kill
```
2025-07-02T23:08:27.140+08:00  INFO 2181 --- [demo] [nio-8080-exec-1] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring DispatcherServlet 'dispatcherServlet'
2025-07-02T23:08:27.140+08:00  INFO 2181 --- [demo] [nio-8080-exec-1] o.s.web.servlet.DispatcherServlet        : Initializing Servlet 'dispatcherServlet'
2025-07-02T23:08:27.141+08:00  INFO 2181 --- [demo] [nio-8080-exec-1] o.s.web.servlet.DispatcherServlet        : Completed initialization in 1 ms
Killed
```
查看系统日志，可以看到进程直接被kill了
```
Jul  2 23:09:16 shinerio-huoshan kernel: oom-kill:constraint=CONSTRAINT_NONE,nodemask=(null),cpuset=/,mems_allowed=0-1,global_oom,task_memcg=/user.slice/user-0.slice/session-1.scope,task=java,pid=2181,uid=0
Jul  2 23:09:16 shinerio-huoshan kernel: Out of memory: Killed process 2181 (java) total-vm:3353044kB, anon-rss:1445296kB, file-rss:0kB, shmem-rss:0kB, UID:0 pgtables:3164kB oom_score_adj:0
Jul  2 23:09:16 shinerio-huoshan systemd[1]: session-1.scope: A process of this unit has been killed by the OOM killer.
```
### 2.5.2. 测试命令
```shell
curl localhost:8080/unsafe/128
curl localhost:8080/unsafe/release
```
### 2.5.3. arthas观测
unsafe命令直接分配和释放的内存无法通过arthas观察
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507022320627.png)
### 2.5.4. jcmd观测
分配前(无other)
```
-                 Java Heap (reserved=256MB, committed=256MB)
                            (mmap: reserved=256MB, committed=256MB, at peak) 
```
分配后
```
-                 Java Heap (reserved=256MB, committed=256MB)
                            (mmap: reserved=256MB, committed=256MB, at peak)

-                     Other (reserved=128MB, committed=128MB)
                            (malloc=128MB #5) (at peak) 
```
释放后
```
-                 Java Heap (reserved=256MB, committed=256MB)
                            (mmap: reserved=256MB, committed=256MB, at peak) 
                            
-                     Other (reserved=0MB, committed=0MB)
                            (malloc=0MB #7) (peak=128MB #6) 
```
### 2.5.5. rss观测
直接使用unsafe命令分配和释放的内存可以通过jcmd观测
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507022325168.png)
### 2.5.6. pmap观测
输出内存显示为`anon`
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507032332888.png)

## 2.6. JNI
### 2.6.1. 启动
```shell
java -XX:NativeMemoryTracking=detail -Xms256m -Xmx256m -XX:MetaspaceSize=64m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=5,filesize=10M -Djava.library.path=$PROJECT_ROOT/src/main/native -jar demo-0.0.1-SNAPSHOT.jar
```
### 2.6.2. 测试命令
```shell
curl localhost:8080/jni/128
curl localhost:8080/jni/release
```
### 2.6.3. oom
执行`curl localhost:8080/jni/1280`分配超大内存
```
# 应用被系统自动kill
2025-07-03T22:18:00.323+08:00  INFO 23454 --- [demo] [nio-8080-exec-1] o.s.web.servlet.DispatcherServlet        : Completed initialization in 1 ms
Killed

# 系统日志显示oom并自动kill进程
Jul  3 22:18:12 shinerio-huoshan kernel: oom-kill:constraint=CONSTRAINT_NONE,nodemask=(null),cpuset=tuned.service,mems_allowed=0-1,global_oom,task_memcg=/user.slice/user-0.slice/session-7.scope,task=java,pid=23454,uid=0
Jul  3 22:18:12 shinerio-huoshan kernel: Out of memory: Killed process 23454 (java) total-vm:3615188kB, anon-rss:1458780kB, file-rss:0kB, shmem-rss:0kB, UID:0 pgtables:3180kB oom_score_adj:0
Jul  3 22:18:12 shinerio-huoshan systemd[1]: session-7.scope: A process of this unit has been killed by the OOM killer.
```
### 2.6.4. arthas观测
arthas无法观察到通过`jni`直接调用`malloc`分配的内存
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507032232844.png)
### 2.6.5. jcmd观测
arthas无法观察到通过`jni`直接调用`malloc`分配的内存
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507032242304.png)
### 2.6.6. rss观测
通过`jni`直接调用`malloc`分配和释放的内存会体现在系统rss指标上
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507032243038.png)
### 2.6.7. pmap观测
```shell
pmap -x `ps -ef|grep demo|grep -v grep|awk '{print $2}'` | sort -nrk3
```
1. Linux将进程内存虚拟为伪文件/proc/$pid/mem，通过它即可查看进程内存中的数据。
2. tail用于偏移到指定内存段的起始地址，即pmap的第一列，head用于读取指定大小，即pmap的第二列。
3. strings用于找出内存中的字符串数据，less用于查看strings输出的字符串。如果内存中不是字符串，也可以不加strings原样输出
```shell
tail -c +$((0x00007face0000000+1)) /proc/`ps -ef|grep demo|grep -v grep|awk '{print $2}'`/mem|head -c $((11616*1024))|strings|less -S
```
通过jni分配的内存显示为`anno`
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507032314044.png)
# 3. 原理
## 3.1. Bits类
`Bits.reserveMemory`和`Bits.unreserveMemory`会更新JVM内部的直接内存计数器，Arthas可以通过读取这些计数器来显示直接内存使用情况。
使用Unsafe方法直接分配和释放的内存，没有经过Bits管理，因此arthas无法观测到。
```java
Bits.reserveMemory(size, cap);
Bits.unreserveMemory(size, cap);
```
## 3.2. DirectByteBuffer
非jvm管理的内存典型代表为`DirectByteBuffer`，jvm gc只能回收`DirectByteBuffer`对象本身，而无法管理其内部通过Unsafe申请的内存。当`DirectByteBuffer`对象被GC回收时，JVM会通过`Cleaner`机制调用本地方法。

具体来说，Cleaner类继承自`PhantomReference`，实现了clean方法。JVM启动时会创建一个名为`Reference Handler`的守护线程，其优先级为`MAX_PRIORITY`（10），该线程不断从`ReferenceQueue`中取出引用对象，并调用其clean方法。
```java
public class Cleaner extends PhantomReference<Object>
```
> [!note]
> JVM垃圾回收没有直接回收DBB对象通过Unsafe方法分配的内存，而是通过其Cleaner机制实现了间接释放。内存的分配和释放都是由系统malloc()和free()函数实现的。
## 3.3. metaspace和class space
Metaspace区域位于堆外，最大内存大小取决于系统内存，而不是堆大小，可以通过指定 `MaxMetaspaceSize`参数来限制最大内存。
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507022031191.png)
虽然每个Java类都关联了一个`java.lang.Class`的实例，而且它是一个贮存在堆中的 Java 对象。但是类的class metadata不是一个Java对象，它不在堆中，而是在 Metaspace 中。

有两个核心配置参数：
- `-XX:MaxMetaspaceSize`：Metaspace 总空间的最大允许使用内存，默认是不限制。
- `-XX:CompressedClassSpaceSize`：Metaspace中的Compressed Class Space的最大允许内存，默认值是1G，这部分会在JVM启动的时候向操作系统申请1G的**虚拟地址映射**，但不是真的就用了操作系统的1G内存。
### 3.3.1. 分配
当一个类被加载时，它的类加载器会负责在Metaspace中分配空间用于存放这个类的元数据。如下图，可以看到在`Id`这个类加载器第一次加载类`X` 和 `Y` 的时候，在 Metaspace 中为它们开辟空间存放元信息。
![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507022018605.png)
### 3.3.2. 回收
分配给一个类的空间，是归属于这个类的类加载器的，只有当这个类加载器卸载的时候，这个空间才会被释放。所以，只有当这个类加载器加载的所有类都没有存活的对象，并且没有到达这些类和类加载器的引用时，相应的Metaspace空间才会被GC释放。

![image.png](https://shinerio.oss-cn-beijing.aliyuncs.com/obsidian202507022019804.png)

### 3.3.3. 系统内存回收
释放Metaspace的空间，并不意味着将这部分空间还给系统内存，这部分空间通常会被JVM保留下来。

这部分被保留的空间有多大，取决于Metaspace的碎片化程度。另外，Metaspace中有一部分区域Compressed Class Space是一定不会还给操作系统的。
# 4. ref
1. [深入理解堆外内存 Metaspace](https://www.javadoop.com/post/metaspace)
2. [Understanding Metaspace and Class Space GC Log Entries](https://poonamparhar.github.io/understanding-metaspace-gc-logs/)
3. [一次Java内存占用高的排查案例，解释了我对内存问题的所有疑问](https://www.cnblogs.com/codelogs/p/17659370.html "发布于 2023-08-26 19:46")