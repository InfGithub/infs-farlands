package com.inf.farlands.mixin.debug.log;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 反序 BoundingBox 的两处读数：构造入口，以及把界搬成反序的原地 move。
 *
 * <p>构造入口是唯一入口：CODEC 与 STREAM_CODEC 的解码，fromCorners、moved 与 inflatedBy 这些工厂，
 * 最后都落到这里。所以这一处读数同时覆盖服务端解码、客户端解码与本地构造三处来源。原版那句由重定向
 * 无条件吞掉，不随开关变化：它按构造次数逐条打，密度由调用方决定，留着就是刷屏。
 *
 * <p>原地 move 是另一处来源：它给两端加同一个增量，两端跨环绕点的次数不同时，有符号序就翻，而这次
 * 搬移不经过构造器，既不报错也不修复，构造那一处的读数看不见它。复制型的 moved 不走这里，它落到
 * 构造器，由上面那条覆盖。模板盒是这条路径的常客：StructureTemplate 先用 fromCorners 造局部盒，再用
 * 原地 move 搬到放置坐标，所以模板只要在极角处越过 int 上界就静默反序。
 *
 * <p>放行判据按调用者签名：签名与上次相同且相隔不足 1 秒才压，签名一变立即放行。只按时间压会把同一
 * 秒里出现的新来源一并埋掉，而被埋的那一条此后再无任何输出。swallowed 记的是自上次放行以来被压掉的
 * 条数，读它时要知道它属于上一个签名。
 *
 * <p>一次日志事件打完全部内容：坐标、自上次放行以来被压掉的条数、以及完整的调用栈。栈不截断，帧数由
 * 调用深度决定；缺一帧就可能缺掉要定位的那一格。
 *
 * <p>反序之后原版把 min 与 max 对调，那是修复行为；move 的字段写入与返回值也不动，这里只观测。
 */
@Mixin(BoundingBox.class)
public class BoundingBoxInvertedLogMixin {

    /** 构造反序的前缀，与 FLDUMP、FLSTAGE、FLRENDER 同族，供 grep 定位。 */
    private static final String TAG_BOX = "FLBOX";

    /** 原地搬移成反序的前缀，与构造那一处分开，便于按发出端分流。 */
    private static final String TAG_MOVE = "FLBOXM";

    /** 同一个调用者签名被压住的时间上限，毫秒。 */
    private static final long INTERVAL_MS = 1000L;

    /** 签名取被观测方法之上的帧数。取多了会把同一来源拆成多个签名，反而压不住刷屏。 */
    private static final int SIGNATURE_FRAMES = 4;

    /** 上次放行的键：前缀加调用者签名。 */
    private static String gateKey = "";

    /** 上次放行的时刻。 */
    private static long gateMillis;

    /** 自上次放行以来被压掉的条数。 */
    private static int gateSwallowed;

    private static final Object GATE_LOCK = new Object();

    /**
     * 构造入口的反序判定与读数。反序判据与紧随其后的原版判定逐字相同，所以在 HEAD 处算出来的结论与
     * 原版分支一致，不需要读实例字段。
     */
    @Inject(method = "<init>(IIIIII)V", at = @At("HEAD"))
    private static void farlands$logInverted(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            CallbackInfo ci) {
        if (maxX >= minX && maxY >= minY && maxZ >= minZ) {
            return;
        }
        if (!FarlandsConfig.logInvertedBoundingBox) {
            return;
        }
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int skipped = farlands$admit(TAG_BOX, stack);
        if (skipped < 0) {
            return;
        }
        InfsFarlands.LOGGER.info("{} inverted min=({},{},{}) max=({},{},{}) swallowed={}{}",
                TAG_BOX, minX, minY, minZ, maxX, maxY, maxZ, skipped, farlands$format(stack));
    }

    /**
     * 原地搬移的读数。注入在 RETURN：此刻六个字段已是搬移后的真实状态，判据直接读它，不必把加法再实现
     * 一遍。pre 由 post 减 delta 得到，int 加法可逆，环绕情形下也精确。
     *
     * <p>method 写全描述符：裸方法名会同时匹配 move(Vec3i)，那条的形参表与本处理体不符。
     */
    @Inject(method = "move(III)Lnet/minecraft/world/level/levelgen/structure/BoundingBox;", at = @At("RETURN"))
    private void farlands$logMoved(int dx, int dy, int dz, CallbackInfoReturnable<BoundingBox> cir) {
        BoundingBox self = (BoundingBox) (Object) this;
        int postMinX = self.minX();
        int postMinY = self.minY();
        int postMinZ = self.minZ();
        int postMaxX = self.maxX();
        int postMaxY = self.maxY();
        int postMaxZ = self.maxZ();
        if (postMaxX >= postMinX && postMaxY >= postMinY && postMaxZ >= postMinZ) {
            return;
        }
        if (!FarlandsConfig.logInvertedBoundingBoxMove) {
            return;
        }
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int skipped = farlands$admit(TAG_MOVE, stack);
        if (skipped < 0) {
            return;
        }
        InfsFarlands.LOGGER.info(
                "{} move-inverted delta=({},{},{}) pre=({},{},{})..({},{},{}) post=({},{},{})..({},{},{}) swallowed={}{}",
                TAG_MOVE,
                dx, dy, dz,
                postMinX - dx, postMinY - dy, postMinZ - dz,
                postMaxX - dx, postMaxY - dy, postMaxZ - dz,
                postMinX, postMinY, postMinZ,
                postMaxX, postMaxY, postMaxZ,
                skipped,
                farlands$format(stack));
    }

    /**
     * 原版那句的接管点。形参表就是被重定向调用的实参表，静态调用没有接收者。
     *
     * <p>处理体空转即吞掉那一条。默认 require 为 1，target 串写错会在应用期抛，不会静默失效。
     */
    @Redirect(method = "<init>(IIIIII)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;logAndPauseIfInIde(Ljava/lang/String;)V"))
    private static void farlands$swallowVanillaLog(String message) {
    }

    /**
     * 放行判据。返回自上次放行以来被压掉的条数，返回 -1 表示本次被压掉。
     *
     * <p>键里带调用者签名，所以同一个来源在循环里重复会被压住，而同一秒里出现的另一个来源照常放行。
     */
    @Unique
    private static int farlands$admit(String tag, StackTraceElement[] stack) {
        String key = tag + '|' + farlands$signature(stack);
        synchronized (GATE_LOCK) {
            long now = System.currentTimeMillis();
            if (key.equals(gateKey) && now - gateMillis < INTERVAL_MS) {
                gateSwallowed++;
                return -1;
            }
            int skipped = gateSwallowed;
            gateSwallowed = 0;
            gateKey = key;
            gateMillis = now;
            return skipped;
        }
    }

    /** 调用者签名：跳过本 mixin 的帧与被观测方法自身，取其上若干帧拼成文本。 */
    @Unique
    private static String farlands$signature(StackTraceElement[] stack) {
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        boolean observed = false;
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().startsWith("com.inf.farlands.mixin.debug.log.")) {
                continue;
            }
            if (!observed) {
                observed = true;
                continue;
            }
            sb.append(frame).append('\n');
            if (++taken >= SIGNATURE_FRAMES) {
                break;
            }
        }
        return sb.toString();
    }

    /** 整条调用栈拼成一段文本，逐帧一行，接在同一条日志事件后面。 */
    @Unique
    private static String farlands$format(StackTraceElement[] stack) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement frame : stack) {
            sb.append("\n    at ").append(frame);
        }
        return sb.toString();
    }
}
