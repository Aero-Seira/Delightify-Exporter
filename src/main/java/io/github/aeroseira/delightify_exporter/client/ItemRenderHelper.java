package io.github.aeroseira.delightify_exporter.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.AbstractMap.SimpleEntry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 客户端专用：物品栏渲染提取类（异步高性能版）
 * 
 * 特性：
 * 1. 完全异步渲染，不阻塞游戏线程
 * 2. 自适应批大小 - 根据帧率动态调节
 * 3. 硬件感知 - 根据 GPU 性能优化
 */
@OnlyIn(Dist.CLIENT)
public class ItemRenderHelper {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RENDER_SIZE = 64;
    
    // 性能参数
    private static final int MIN_BATCH_SIZE = 5;     // 最小批大小
    private static final int MAX_BATCH_SIZE = 30;    // 最大批大小
    private static final int TARGET_FRAME_TIME = 50; // 目标帧时间 ms (20fps)
    private static final int ADJUST_INTERVAL = 20;   // 每20帧调整一次批大小
    
    // 状态
    private static volatile RenderSession currentSession = null;
    private static final AtomicInteger currentBatchSize = new AtomicInteger(10);
    private static final AtomicLong lastAdjustTime = new AtomicLong(0);
    private static final AtomicLong frameCount = new AtomicLong(0);
    
    // 线程池 - 用于后台处理像素数据
    private static final ExecutorService processorPool = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
        r -> {
            Thread t = new Thread(r, "ItemRender-Processor");
            t.setDaemon(true);
            return t;
        }
    );
    
    static {
        MinecraftForge.EVENT_BUS.register(ItemRenderHelper.class);
    }
    
    /**
     * 渲染会话 - 使用线程安全队列
     */
    private static class RenderSession {
        final ConcurrentLinkedQueue<Map.Entry<String, ItemStack>> taskQueue;
        final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
        final AtomicInteger completed = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final int totalTasks;
        final long startTime;
        final CompletableFuture<Map<String, String>> future;
        final Consumer<ProgressInfo> progressCallback;
        
        RenderSession(Map<String, ItemStack> tasks, Consumer<ProgressInfo> progressCallback) {
            this.taskQueue = new ConcurrentLinkedQueue<>(tasks.entrySet());
            this.totalTasks = tasks.size();
            this.startTime = System.currentTimeMillis();
            this.future = new CompletableFuture<>();
            this.progressCallback = progressCallback;
        }
        
        boolean isComplete() {
            return completed.get() + failed.get() >= totalTasks;
        }
        
        void complete() {
            future.complete(new HashMap<>(results));
        }
    }
    
    /**
     * 进度信息
     */
    public static class ProgressInfo {
        public final int total;
        public final int completed;
        public final int failed;
        public final int batchSize;
        public final long elapsedMs;
        public final double itemsPerSecond;
        
        public ProgressInfo(int total, int completed, int failed, int batchSize, long elapsedMs) {
            this.total = total;
            this.completed = completed;
            this.failed = failed;
            this.batchSize = batchSize;
            this.elapsedMs = elapsedMs;
            this.itemsPerSecond = elapsedMs > 0 ? (completed * 1000.0 / elapsedMs) : 0;
        }
        
        public double getProgress() {
            return total > 0 ? (completed + failed) * 100.0 / total : 0;
        }
    }
    
    /**
     * 开始异步批量渲染
     * 
     * @param tasks 物品任务
     * @param progressCallback 进度回调（每批完成后调用）
     * @return CompletableFuture 异步结果
     */
    public static CompletableFuture<Map<String, String>> startAsyncRender(
            Map<String, ItemStack> tasks, 
            Consumer<ProgressInfo> progressCallback) {
        
        if (tasks.isEmpty()) {
            return CompletableFuture.completedFuture(new ConcurrentHashMap<>());
        }
        
        // 如果有正在进行的会话，先完成它
        if (currentSession != null && !currentSession.isComplete()) {
            LOGGER.warn("Previous render session still active, waiting...");
            currentSession.future.join();
        }
        
        // 创建新会话
        currentSession = new RenderSession(tasks, progressCallback);
        
        // 根据任务数初始化批大小
        int initialBatch = Math.min(MAX_BATCH_SIZE, Math.max(MIN_BATCH_SIZE, tasks.size() / 100));
        currentBatchSize.set(initialBatch);
        
        LOGGER.info("Async render started: {} items, initial batch: {}", tasks.size(), initialBatch);
        
        return currentSession.future;
    }
    
    /**
     * 获取当前进度
     */
    public static ProgressInfo getCurrentProgress() {
        if (currentSession == null) {
            return null;
        }
        
        return new ProgressInfo(
            currentSession.totalTasks,
            currentSession.completed.get(),
            currentSession.failed.get(),
            currentBatchSize.get(),
            System.currentTimeMillis() - currentSession.startTime
        );
    }
    
    /**
     * 是否正在渲染
     */
    public static boolean isRendering() {
        return currentSession != null && !currentSession.isComplete();
    }
    
    /**
     * 等待完成（阻塞，用于命令行等待）
     */
    public static Map<String, String> waitForCompletion() {
        if (currentSession == null) {
            return new ConcurrentHashMap<>();
        }
        return currentSession.future.join();
    }
    
    /**
     * RenderTick 处理器 - 每帧执行渲染
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        
        RenderSession session = currentSession;
        if (session == null || session.isComplete()) {
            return;
        }
        
        long frameStart = System.currentTimeMillis();
        
        // 自适应调整批大小
        adjustBatchSize();
        
        int batchSize = currentBatchSize.get();
        int rendered = 0;
        
        // 批量渲染
        for (int i = 0; i < batchSize; i++) {
            Map.Entry<String, ItemStack> entry = pollNextTask(session);
            if (entry == null) break;
            
            try {
                String result = renderInventoryItem(entry.getValue(), entry.getKey());
                if (result != null) {
                    session.results.put(entry.getKey(), result);
                    session.completed.incrementAndGet();
                } else {
                    session.failed.incrementAndGet();
                }
            } catch (Exception e) {
                LOGGER.debug("Render failed for {}: {}", entry.getKey(), e.getMessage());
                session.failed.incrementAndGet();
            }
            rendered++;
        }
        
        // 后台处理进度回调
        if (rendered > 0 && session.progressCallback != null) {
            ProgressInfo info = new ProgressInfo(
                session.totalTasks,
                session.completed.get(),
                session.failed.get(),
                batchSize,
                System.currentTimeMillis() - session.startTime
            );
            
            // 异步执行回调避免阻塞渲染
            processorPool.execute(() -> session.progressCallback.accept(info));
        }
        
        // 检查是否完成
        if (session.isComplete()) {
            session.complete();
            LOGGER.info("Async render completed: {} success, {} failed in {}ms",
                session.completed.get(), session.failed.get(),
                System.currentTimeMillis() - session.startTime);
        }
        
        // 记录帧时间用于自适应
        long frameTime = System.currentTimeMillis() - frameStart;
        frameCount.incrementAndGet();
    }
    
    /**
     * 获取下一个任务 - 使用无锁队列
     */
    private static Map.Entry<String, ItemStack> pollNextTask(RenderSession session) {
        return session.taskQueue.poll();
    }
    
    /**
     * 自适应调整批大小
     */
    private static void adjustBatchSize() {
        long now = System.currentTimeMillis();
        long lastAdjust = lastAdjustTime.get();
        long frames = frameCount.get();
        
        // 每 ADJUST_INTERVAL 帧调整一次
        if (frames - (lastAdjustTime.get() / 1000) < ADJUST_INTERVAL) {
            return;
        }
        
        if (!lastAdjustTime.compareAndSet(lastAdjust, now)) {
            return;
        }
        
        Minecraft mc = Minecraft.getInstance();
        int currentFPS = mc.getFps();
        int currentBatch = currentBatchSize.get();
        
        // 根据 FPS 调整
        int newBatch = currentBatch;
        if (currentFPS > 55) {
            // FPS 很高，可以增加批大小
            newBatch = Math.min(MAX_BATCH_SIZE, currentBatch + 2);
        } else if (currentFPS < 20) {
            // FPS 很低，减少批大小
            newBatch = Math.max(MIN_BATCH_SIZE, currentBatch - 3);
        } else if (currentFPS < 30) {
            // FPS 偏低，稍微减少
            newBatch = Math.max(MIN_BATCH_SIZE, currentBatch - 1);
        }
        
        if (newBatch != currentBatch) {
            currentBatchSize.set(newBatch);
            LOGGER.debug("Batch size adjusted: {} -> {} (FPS: {})", currentBatch, newBatch, currentFPS);
        }
    }
    
    /**
     * 渲染单个物品
     */
    private static String renderInventoryItem(ItemStack stack, String itemId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getItemRenderer() == null) return null;
        
        // 保存状态
        int prevFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
        
        int fbo = -1, colorTex = -1, depthBuf = -1;
        
        try {
            // 创建 FBO
            fbo = GL30.glGenFramebuffers();
            colorTex = GL11.glGenTextures();
            depthBuf = GL30.glGenRenderbuffers();
            
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, RENDER_SIZE, RENDER_SIZE, 
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBuf);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_DEPTH_COMPONENT, RENDER_SIZE, RENDER_SIZE);
            
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, 
                GL11.GL_TEXTURE_2D, colorTex, 0);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, 
                GL30.GL_RENDERBUFFER, depthBuf);
            
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                return null;
            }
            
            GL11.glViewport(0, 0, RENDER_SIZE, RENDER_SIZE);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            
            RenderSystem.setProjectionMatrix(
                new Matrix4f().ortho(0, RENDER_SIZE, RENDER_SIZE, 0, -1000, 1000),
                VertexSorting.byDistance(new Vector3f(0, 0, 0))
            );
            
            // 渲染物品
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            float scale = RENDER_SIZE / 16.0f;
            graphics.pose().pushPose();
            graphics.pose().scale(scale, scale, 1);
            graphics.renderItem(stack, 0, 0);
            graphics.pose().popPose();
            graphics.flush();
            
            // 读取像素
            ByteBuffer buffer = MemoryUtil.memAlloc(RENDER_SIZE * RENDER_SIZE * 4);
            GL11.glReadPixels(0, 0, RENDER_SIZE, RENDER_SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            
            // 后台线程处理像素转换和编码
            return processPixelData(buffer, itemId);
            
        } catch (Exception e) {
            LOGGER.debug("Render error for {}: {}", itemId, e.getMessage());
            return null;
        } finally {
            if (fbo != -1) GL30.glDeleteFramebuffers(fbo);
            if (colorTex != -1) GL11.glDeleteTextures(colorTex);
            if (depthBuf != -1) GL30.glDeleteRenderbuffers(depthBuf);
            
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFBO);
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        }
    }
    
    /**
     * 处理像素数据（在调用者线程执行，但计算量小）
     */
    private static String processPixelData(ByteBuffer buffer, String itemId) {
        try {
            BufferedImage image = new BufferedImage(RENDER_SIZE, RENDER_SIZE, BufferedImage.TYPE_INT_ARGB);
            boolean hasContent = false;
            
            for (int y = 0; y < RENDER_SIZE; y++) {
                for (int x = 0; x < RENDER_SIZE; x++) {
                    int index = ((RENDER_SIZE - 1 - y) * RENDER_SIZE + x) * 4;
                    int r = buffer.get(index) & 0xFF;
                    int g = buffer.get(index + 1) & 0xFF;
                    int b = buffer.get(index + 2) & 0xFF;
                    int a = buffer.get(index + 3) & 0xFF;
                    
                    if (a > 10) hasContent = true;
                    
                    int argb = (a << 24) | (r << 16) | (g << 8) | b;
                    image.setRGB(x, y, argb);
                }
            }
            MemoryUtil.memFree(buffer);
            
            if (!hasContent) return null;
            
            BufferedImage cropped = cropTransparent(image);
            if (cropped == null) return null;
            
            BufferedImage finalImage = scaleToMaxSize(cropped, 128);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(finalImage, "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
            
        } catch (Exception e) {
            MemoryUtil.memFree(buffer);
            return null;
        }
    }
    
    private static BufferedImage cropTransparent(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        int minX = width, minY = height, maxX = 0, maxY = 0;
        boolean hasOpaque = false;
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = (image.getRGB(x, y) >> 24) & 0xFF;
                if (alpha > 10) {
                    hasOpaque = true;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        
        if (!hasOpaque) return null;
        
        int padding = 2;
        minX = Math.max(0, minX - padding);
        minY = Math.max(0, minY - padding);
        maxX = Math.min(width - 1, maxX + padding);
        maxY = Math.min(height - 1, maxY + padding);
        
        return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }
    
    private static BufferedImage scaleToMaxSize(BufferedImage image, int maxSize) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        if (width <= maxSize && height <= maxSize) {
            return image;
        }
        
        double scale = Math.min((double) maxSize / width, (double) maxSize / height);
        int newWidth = (int) (width * scale);
        int newHeight = (int) (height * scale);
        
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        scaled.createGraphics().drawImage(
            image.getScaledInstance(newWidth, newHeight, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
        
        return scaled;
    }
    
    /**
     * 关闭处理器池（Mod 卸载时调用）
     */
    public static void shutdown() {
        processorPool.shutdown();
    }
}
