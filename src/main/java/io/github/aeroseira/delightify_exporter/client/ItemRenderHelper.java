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
import java.util.Base64;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 客户端专用：物品栏渲染提取类
 * 
 * 模拟物品栏 GUI 的渲染方式，在离屏缓冲区渲染物品
 * 获得和玩家打开物品栏时看到的完全一致的效果
 */
@OnlyIn(Dist.CLIENT)
public class ItemRenderHelper {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RENDER_SIZE = 64; // 渲染目标大小
    private static final int BATCH_SIZE = 5;   // 每帧渲染数量
    
    // 渲染状态
    private static final List<RenderTask> renderTasks = new ArrayList<>();
    private static final Map<String, String> renderResults = new ConcurrentHashMap<>();
    private static volatile int currentIndex = 0;
    private static volatile boolean isRendering = false;
    private static volatile boolean isComplete = false;
    
    private static class RenderTask {
        final String itemId;
        final ItemStack stack;
        
        RenderTask(String itemId, ItemStack stack) {
            this.itemId = itemId;
            this.stack = stack;
        }
    }
    
    static {
        MinecraftForge.EVENT_BUS.register(ItemRenderHelper.class);
    }
    
    /**
     * 在 RenderTick 中处理渲染
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isRendering) {
            return;
        }
        
        if (currentIndex >= renderTasks.size()) {
            isComplete = true;
            isRendering = false;
            return;
        }
        
        Minecraft mc = Minecraft.getInstance();
        
        // 每帧渲染多个物品
        for (int i = 0; i < BATCH_SIZE && currentIndex < renderTasks.size(); i++) {
            RenderTask task = renderTasks.get(currentIndex);
            
            try {
                String result = renderInventoryItem(mc, task.stack, task.itemId);
                if (result != null) {
                    renderResults.put(task.itemId, result);
                }
            } catch (Exception e) {
                LOGGER.error("Render failed for {}: {}", task.itemId, e.getMessage());
            }
            
            currentIndex++;
        }
        
        // 打印进度
        if (currentIndex % 100 == 0 || currentIndex >= renderTasks.size()) {
            LOGGER.info("Rendered {}/{} items", currentIndex, renderTasks.size());
        }
    }
    
    /**
     * 批量渲染物品
     */
    public static Map<String, String> batchRender(Map<String, ItemStack> tasks) {
        renderResults.clear();
        renderTasks.clear();
        currentIndex = 0;
        isComplete = false;
        
        if (tasks.isEmpty()) {
            return renderResults;
        }
        
        // 准备任务
        for (var entry : tasks.entrySet()) {
            renderTasks.add(new RenderTask(entry.getKey(), entry.getValue()));
        }
        
        isRendering = true;
        
        LOGGER.info("Batch render started: {} items", renderTasks.size());
        long startTime = System.currentTimeMillis();
        
        // 等待渲染完成
        while (isRendering && !isComplete) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            
            // 超时检查（3分钟）
            if (System.currentTimeMillis() - startTime > 180000) {
                LOGGER.error("Render timeout");
                isRendering = false;
                break;
            }
        }
        
        long elapsed = System.currentTimeMillis() - startTime;
        LOGGER.info("Batch render completed in {}ms: {}/{} items", 
            elapsed, renderResults.size(), renderTasks.size());
        
        return new HashMap<>(renderResults);
    }
    
    /**
     * 渲染单个物品（物品栏样式）
     */
    private static String renderInventoryItem(Minecraft mc, ItemStack stack, String itemId) {
        if (mc.getItemRenderer() == null) {
            return null;
        }
        
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
            
            // 颜色纹理
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, RENDER_SIZE, RENDER_SIZE, 
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            
            // 深度缓冲
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBuf);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_DEPTH_COMPONENT, RENDER_SIZE, RENDER_SIZE);
            
            // 绑定 FBO
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, 
                GL11.GL_TEXTURE_2D, colorTex, 0);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, 
                GL30.GL_RENDERBUFFER, depthBuf);
            
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                return null;
            }
            
            // 设置视口和清除
            GL11.glViewport(0, 0, RENDER_SIZE, RENDER_SIZE);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            
            // 关键：设置正交投影（和 GUI 一致）
            RenderSystem.setProjectionMatrix(
                new Matrix4f().ortho(0, RENDER_SIZE, RENDER_SIZE, 0, -1000, 1000),
                VertexSorting.byDistance(new Vector3f(0, 0, 0))
            );
            
            // 创建 GuiGraphics（物品栏使用的渲染类）
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            
            // 设置缩放，使 16x16 物品填满画面
            float scale = RENDER_SIZE / 16.0f;
            graphics.pose().pushPose();
            graphics.pose().scale(scale, scale, 1);
            
            // 关键：使用 renderItem - 和物品栏 GUI 完全一致的方法！
            graphics.renderItem(stack, 0, 0);
            
            graphics.pose().popPose();
            graphics.flush();
            
            // 读取像素
            ByteBuffer buffer = MemoryUtil.memAlloc(RENDER_SIZE * RENDER_SIZE * 4);
            GL11.glReadPixels(0, 0, RENDER_SIZE, RENDER_SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            
            // 转换为 BufferedImage
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
            
            if (!hasContent) {
                LOGGER.debug("Empty render for {}", itemId);
                return null;
            }
            
            // 裁剪和缩放
            BufferedImage cropped = cropTransparent(image);
            if (cropped == null) return null;
            
            BufferedImage finalImage = scaleToMaxSize(cropped, 128);
            
            // 编码
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(finalImage, "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
            
        } catch (Exception e) {
            LOGGER.error("Render error for {}: {}", itemId, e.getMessage());
            return null;
        } finally {
            // 清理
            if (fbo != -1) GL30.glDeleteFramebuffers(fbo);
            if (colorTex != -1) GL11.glDeleteTextures(colorTex);
            if (depthBuf != -1) GL30.glDeleteRenderbuffers(depthBuf);
            
            // 恢复状态
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFBO);
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
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
}
