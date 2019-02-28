package org.kin.framework.hotswap;

import org.kin.framework.Closeable;
import org.kin.framework.hotswap.agent.JavaAgentHotswapFactory;
import org.kin.framework.utils.ClassUtils;
import org.kin.framework.utils.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Created by huangjianqin on 2018/2/1.
 * 文件监听器   单例模式
 * 利用nio 新api监听文件变换
 * 该api底层本质上是监听了操作系统的文件系统触发的文件更改事件
 * <p>
 * 异步热加载文件 同步类热更新
 */
public class FileMonitor extends Thread implements Closeable{
    private static final Logger log = LoggerFactory.getLogger("FileMonitor");
    //默认实现
    private static final FileMonitor monitor = new FileMonitor();
    private static boolean isStarted = false;

    private WatchService watchService;
    //hash(file name) -> Reloadable 实例
    private Map<Integer, FileReloadable> monitorItems;
    //类热加载工厂
    private HotswapFactory hotswapFactory;
    //分段锁
    private Object[] locks;
    //异步热加载文件 执行线程
    private ExecutorService executorService;
    private boolean isStopped = false;

    private FileMonitor() {
    }

    public static FileMonitor instance() {
        if (!isStarted) {
            monitor.start();
        }
        return monitor;
    }

    private void init() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            ExceptionUtils.log(e);
        }

        monitorItems = new HashMap<>();
        locks = new Object[5];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        if (hotswapFactory == null) {
            //默认设置
            hotswapFactory = new CommonHotswapFactory();
        }
        if (executorService == null) {
            //默认设置
            executorService = Executors.newFixedThreadPool(
                    5,
                    r -> {
                        Thread thread = new Thread(r);
                        thread.setName("file-monitor-reload-thread");
                        return thread;
                    }
            );
        }

        if (hotswapFactory instanceof CommonHotswapFactory || hotswapFactory instanceof JavaAgentHotswapFactory) {
            monitorClasspath();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdown();
        }));
    }

    public FileMonitor hotswapFactory(HotswapFactory hotswapFactory) {
        this.hotswapFactory = hotswapFactory;
        return this;
    }

    public FileMonitor executor(ExecutorService executorService) {
        this.executorService = executorService;
        return this;
    }

    /**
     * 监听整个classpath
     */
    private void monitorClasspath() {
        String classRoot = Thread.currentThread().getContextClassLoader().getResource("").getPath();
        Path classRootPath = Paths.get(classRoot);
        try {
            Stream<Path> allDir = Files.walk(classRootPath).filter(p -> Files.isDirectory(p));
            allDir.forEach(p -> {
                try {
                    p.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                } catch (IOException e) {
                    ExceptionUtils.log(e);
                }
            });
        } catch (IOException e) {
            ExceptionUtils.log(e);
        }
    }

    @Override
    public synchronized void start() {
        init();
        super.start();
    }

    @Override
    public void run() {
        log.info("file monitor start");
        while (!isStopped && !Thread.currentThread().isInterrupted()) {
            List<Path> changedClasses = new ArrayList<>();
            try {
                WatchKey key = watchService.take();
                //变化的路径
                Path parentPath = (Path) key.watchable();
                List<WatchEvent<?>> events = key.pollEvents();
                events.forEach(event -> {
                    //变化item的名字(文件名或者文件夹名)
                    String itemName = event.context().toString();
                    int hashKey = itemName.hashCode();
                    //真实路径
                    Path childPath = Paths.get(parentPath.toString(), itemName);
                    log.debug("'{}' changed", childPath.toString());
                    if (!Files.isDirectory(childPath)) {
                        //非文件夹
                        if (itemName.endsWith(ClassUtils.CLASS_SUFFIX)) {
                            //处理类热更新
                            changedClasses.add(childPath);
                        } else {
                            synchronized (getLock(hashKey)) {
                                //处理文件热更新
                                FileReloadable fileReloadable = monitorItems.get(hashKey);
                                if (fileReloadable != null) {
                                    executorService.execute(() -> {
                                        try {
                                            try (InputStream is = new FileInputStream(childPath.toFile())) {
                                                fileReloadable.reload(is);
                                            }
                                        } catch (IOException e) {
                                            ExceptionUtils.log(e);
                                        }
                                    });
                                }
                            }
                        }
                    }
                });
                //重置状态，让key等待事件
                key.reset();
            } catch (InterruptedException e) {
                ExceptionUtils.log(e);
            }

            if (changedClasses.size() > 0) {
                //类热更新
                executorService.execute(() -> hotswapFactory.reload(changedClasses));
                HotFix.instance().fix();
            }
        }
        log.info("file monitor end");
    }

    /**
     * 获取分段锁
     */
    private Object getLock(int key) {
        return locks[key % locks.length];
    }

    public void shutdown() {
        checkStatus();

        isStopped = true;
        try {
            watchService.close();
        } catch (IOException e) {
            ExceptionUtils.log(e);
        }
        executorService.shutdown();
        //help GC
        monitorItems = null;
        hotswapFactory = null;
        locks = null;
        executorService = null;

        //中断监控线程, 让本线程退出
        interrupt();
    }

    private void checkStatus() {
        if (isStopped) {
            throw new IllegalStateException("file monitor has shutdowned");
        }
    }

    /**
     * @param classReloadable 监听该实例
     */
    public void monitorObject(ClassReloadable classReloadable) {
        checkStatus();
        if (hotswapFactory instanceof CommonHotswapFactory) {
            ((CommonHotswapFactory) hotswapFactory).register(classReloadable);
        }
    }

    public void monitorFile(String pathStr, FileReloadable fileReloadable) {
        checkStatus();
        Path path = Paths.get(pathStr);
        monitorFile(path, fileReloadable);
    }

    public void monitorFile(Path path, FileReloadable fileReloadable) {
        checkStatus();
        monitorFile0(path.getParent(), path.getFileName().toString(), fileReloadable);
    }

    /**
     * 监听文件变化
     */
    private void monitorFile0(Path dir, String itemName, FileReloadable fileReloadable) {
        try {
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            ExceptionUtils.log(e);
        }

        int key = itemName.hashCode();
        synchronized (getLock(key)) {
            monitorItems.put(key, fileReloadable);
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}
