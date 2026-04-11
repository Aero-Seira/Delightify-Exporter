package io.github.aeroseira.delightify_exporter.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
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
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 客户端专用：配方视图渲染器
 * 
 * 为每种配方类型渲染一个示例界面图片
 * 包含：背景、槽位标记、箭头等元素
 */
@OnlyIn(Dist.CLIENT)
public class RecipeViewRenderHelper {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RENDER_WIDTH = 200;
    private static final int RENDER_HEIGHT = 120;
    
    // 默认配方界面尺寸
    private static final int GUI_WIDTH = 176;
    private static final int GUI_HEIGHT = 85;
    
    // 性能参数
    private static final int MIN_BATCH_SIZE = 3;
    private static final int MAX_BATCH_SIZE = 10;
    
    // 状态
    private static volatile RenderSession currentSession = null;
    private static final AtomicInteger currentBatchSize = new AtomicInteger(5);
    
    static {
        MinecraftForge.EVENT_BUS.register(RecipeViewRenderHelper.class);
    }
    
    /**
     * 渲染任务
     */
    public static class RenderTask {
        public final String typeId;
        public final JsonObject layout;
        
        public RenderTask(String typeId, JsonObject layout) {
            this.typeId = typeId;
            this.layout = layout;
        }
    }
    
    /**
     * 渲染结果
     */
    public static class RenderResult {
        public final String typeId;
        public final String base64Png;
        public final JsonObject layout;
        
        public RenderResult(String typeId, String base64Png, JsonObject layout) {
            this.typeId = typeId;
            this.base64Png = base64Png;
            this.layout = layout;
        }
    }
    
    /**
     * 渲染会话
     */
    private static class RenderSession {
        final ConcurrentLinkedQueue<RenderTask> taskQueue;
        final ConcurrentHashMap<String, RenderResult> results = new ConcurrentHashMap<>();
        final AtomicInteger completed = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final int totalTasks;
        final long startTime;
        final CompletableFuture<Map<String, RenderResult>> future;
        final Consumer<ProgressInfo> progressCallback;
        
        RenderSession(Map<String, RenderTask> tasks, Consumer<ProgressInfo> progressCallback) {
            this.taskQueue = new ConcurrentLinkedQueue<>(tasks.values());
            this.totalTasks = tasks.size();
            this.startTime = System.currentTimeMillis();
            this.future = new CompletableFuture<>();
            this.progressCallback = progressCallback;
        }
        
        boolean isComplete() {
            return completed.get() + failed.get() >= totalTasks;
        }
        
        void complete() {
            future.complete(new ConcurrentHashMap<>(results));
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
     */
    public static CompletableFuture<Map<String, RenderResult>> startAsyncRender(
            Map<String, RenderTask> tasks, 
            Consumer<ProgressInfo> progressCallback) {
        
        if (tasks.isEmpty()) {
            return CompletableFuture.completedFuture(new ConcurrentHashMap<>());
        }
        
        // 如果有正在进行的会话，先完成它
        if (currentSession != null && !currentSession.isComplete()) {
            LOGGER.warn("Previous recipe view render session still active, waiting...");
            currentSession.future.join();
        }
        
        currentSession = new RenderSession(tasks, progressCallback);
        
        LOGGER.info("Recipe view async render started: {} types", tasks.size());
        
        return currentSession.future;
    }
    
    /**
     * 等待完成
     */
    public static Map<String, RenderResult> waitForCompletion() {
        if (currentSession == null) {
            return new ConcurrentHashMap<>();
        }
        return currentSession.future.join();
    }
    
    /**
     * 是否正在渲染
     */
    public static boolean isRendering() {
        return currentSession != null && !currentSession.isComplete();
    }
    
    /**
     * RenderTick 处理器
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
        
        int batchSize = currentBatchSize.get();
        int rendered = 0;
        
        for (int i = 0; i < batchSize; i++) {
            RenderTask task = session.taskQueue.poll();
            if (task == null) break;
            
            try {
                RenderResult result = renderRecipeView(task);
                if (result != null && result.base64Png != null) {
                    session.results.put(task.typeId, result);
                    session.completed.incrementAndGet();
                } else {
                    session.failed.incrementAndGet();
                }
            } catch (Exception e) {
                LOGGER.debug("Render failed for {}: {}", task.typeId, e.getMessage());
                session.failed.incrementAndGet();
            }
            rendered++;
        }
        
        // 进度回调
        if (rendered > 0 && session.progressCallback != null) {
            ProgressInfo info = new ProgressInfo(
                session.totalTasks,
                session.completed.get(),
                session.failed.get(),
                batchSize,
                System.currentTimeMillis() - session.startTime
            );
            session.progressCallback.accept(info);
        }
        
        // 检查是否完成
        if (session.isComplete()) {
            session.complete();
            LOGGER.info("Recipe view render completed: {} success, {} failed in {}ms",
                session.completed.get(), session.failed.get(),
                System.currentTimeMillis() - session.startTime);
        }
    }
    
    /**
     * 渲染单个配方视图
     */
    private static RenderResult renderRecipeView(RenderTask task) {
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
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, RENDER_WIDTH, RENDER_HEIGHT, 
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBuf);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_DEPTH_COMPONENT, RENDER_WIDTH, RENDER_HEIGHT);
            
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, 
                GL11.GL_TEXTURE_2D, colorTex, 0);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, 
                GL30.GL_RENDERBUFFER, depthBuf);
            
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                return new RenderResult(task.typeId, null, task.layout);
            }
            
            GL11.glViewport(0, 0, RENDER_WIDTH, RENDER_HEIGHT);
            GL11.glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            
            RenderSystem.setProjectionMatrix(
                new Matrix4f().ortho(0, RENDER_WIDTH, RENDER_HEIGHT, 0, -1000, 1000),
                VertexSorting.byDistance(new Vector3f(0, 0, 0))
            );
            
            // 渲染配方界面
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            renderRecipeGui(graphics, task);
            graphics.flush();
            
            // 读取像素
            ByteBuffer buffer = MemoryUtil.memAlloc(RENDER_WIDTH * RENDER_HEIGHT * 4);
            GL11.glReadPixels(0, 0, RENDER_WIDTH, RENDER_HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            
            String base64 = processPixelData(buffer);
            
            return new RenderResult(task.typeId, base64, task.layout);
            
        } catch (Exception e) {
            LOGGER.debug("Render error for {}: {}", task.typeId, e.getMessage());
            return new RenderResult(task.typeId, null, task.layout);
        } finally {
            if (fbo != -1) GL30.glDeleteFramebuffers(fbo);
            if (colorTex != -1) GL11.glDeleteTextures(colorTex);
            if (depthBuf != -1) GL30.glDeleteRenderbuffers(depthBuf);
            
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFBO);
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        }
    }
    
    /**
     * 渲染配方 GUI
     */
    private static void renderRecipeGui(GuiGraphics graphics, RenderTask task) {
        JsonObject layout = task.layout;
        String typeId = task.typeId;
        
        // 获取尺寸
        int width = GUI_WIDTH;
        int height = GUI_HEIGHT;
        if (layout.has("size")) {
            JsonObject size = layout.getAsJsonObject("size");
            width = size.has("w") ? size.get("w").getAsInt() : GUI_WIDTH;
            height = size.has("h") ? size.get("h").getAsInt() : GUI_HEIGHT;
        }
        
        // 居中计算
        int offsetX = (RENDER_WIDTH - width) / 2;
        int offsetY = (RENDER_HEIGHT - height) / 2;
        
        // 绘制背景
        renderBackground(graphics, offsetX, offsetY, width, height, typeId);
        
        // 绘制槽位
        if (layout.has("slots")) {
            JsonArray slots = layout.getAsJsonArray("slots");
            for (int i = 0; i < slots.size(); i++) {
                JsonObject slot = slots.get(i).getAsJsonObject();
                renderSlot(graphics, offsetX, offsetY, slot);
            }
        }
        
        // 绘制控件
        if (layout.has("widgets")) {
            JsonArray widgets = layout.getAsJsonArray("widgets");
            for (int i = 0; i < widgets.size(); i++) {
                JsonObject widget = widgets.get(i).getAsJsonObject();
                renderWidget(graphics, offsetX, offsetY, widget);
            }
        }
    }
    
    /**
     * 绘制背景
     */
    private static void renderBackground(GuiGraphics graphics, int offsetX, int offsetY, 
                                         int width, int height, String typeId) {
        // 绘制深色背景
        graphics.fill(offsetX, offsetY, offsetX + width, offsetY + height, 0xFF333333);
        // 绘制边框
        graphics.fill(offsetX, offsetY, offsetX + width, offsetY + 1, 0xFF555555);
        graphics.fill(offsetX, offsetY + height - 1, offsetX + width, offsetY + height, 0xFF555555);
        graphics.fill(offsetX, offsetY, offsetX + 1, offsetY + height, 0xFF555555);
        graphics.fill(offsetX + width - 1, offsetY, offsetX + width, offsetY + height, 0xFF555555);
        
        // 尝试加载原版背景纹理
        ResourceLocation bgTexture = getBackgroundTexture(typeId);
        if (bgTexture != null) {
            try {
                graphics.blit(bgTexture, offsetX, offsetY, 0, 0, width, height, 256, 256);
            } catch (Exception e) {
                // 纹理加载失败，使用默认背景
            }
        }
    }
    
    /**
     * 获取背景纹理
     */
    private static ResourceLocation getBackgroundTexture(String typeId) {
        return switch (typeId) {
            case "minecraft:crafting_shaped", "minecraft:crafting_shapeless" ->
                new ResourceLocation("minecraft", "textures/gui/container/crafting_table.png");
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking" ->
                new ResourceLocation("minecraft", "textures/gui/container/furnace.png");
            case "minecraft:stonecutting" ->
                new ResourceLocation("minecraft", "textures/gui/container/stonecutter.png");
            case "minecraft:smithing_transform", "minecraft:smithing_trim" ->
                new ResourceLocation("minecraft", "textures/gui/container/smithing.png");
            case "minecraft:campfire_cooking" ->
                new ResourceLocation("minecraft", "textures/gui/container/campfire.png");
            default -> null;
        };
    }
    
    /**
     * 绘制槽位
     */
    private static void renderSlot(GuiGraphics graphics, int offsetX, int offsetY, JsonObject slot) {
        int x = offsetX + slot.get("x").getAsInt();
        int y = offsetY + slot.get("y").getAsInt();
        int w = slot.get("w").getAsInt();
        int h = slot.get("h").getAsInt();
        String role = slot.get("role").getAsString();
        
        // 根据角色选择颜色
        int color = switch (role) {
            case "input" -> 0xFF6666CC;
            case "output" -> 0xFF66CC66;
            case "catalyst" -> 0xFFCC9966;
            default -> 0xFF999999;
        };
        
        // 绘制槽位背景
        graphics.fill(x, y, x + w, y + h, 0xFF222222);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
    }
    
    /**
     * 绘制控件
     */
    private static void renderWidget(GuiGraphics graphics, int offsetX, int offsetY, JsonObject widget) {
        String kind = widget.get("kind").getAsString();
        int x = offsetX + widget.get("x").getAsInt();
        int y = offsetY + widget.get("y").getAsInt();
        
        switch (kind) {
            case "arrow" -> {
                // 绘制箭头
                graphics.fill(x, y + 4, x + 20, y + 10, 0xFFAAAAAA);
                // 箭头头部
                graphics.fill(x + 16, y + 2, x + 22, y + 12, 0xFFAAAAAA);
            }
            case "flame" -> {
                // 绘制火焰
                graphics.fill(x + 4, y + 2, x + 10, y + 14, 0xFFFF6600);
                graphics.fill(x + 2, y + 6, x + 12, y + 16, 0xFFFF8800);
            }
        }
    }
    
    /**
     * 处理像素数据
     */
    private static String processPixelData(ByteBuffer buffer) {
        try {
            BufferedImage image = new BufferedImage(RENDER_WIDTH, RENDER_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            boolean hasContent = false;
            
            for (int y = 0; y < RENDER_HEIGHT; y++) {
                for (int x = 0; x < RENDER_WIDTH; x++) {
                    int index = ((RENDER_HEIGHT - 1 - y) * RENDER_WIDTH + x) * 4;
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
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
            
        } catch (Exception e) {
            MemoryUtil.memFree(buffer);
            return null;
        }
    }
}
