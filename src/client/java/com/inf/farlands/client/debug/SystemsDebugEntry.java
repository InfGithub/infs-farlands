package com.inf.farlands.client.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.client.network.ClientSystems;
import com.inf.farlands.terrain.registry.SystemsData.Arg;
import com.inf.farlands.terrain.registry.SystemsData.LevelSelection;
import com.inf.farlands.terrain.registry.SystemsData.SystemSelection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 调试界面上的当前 level 四族系统。
 *
 * <p>只读客户端收到的那份，服务端在玩家进服或换维度后下发一次。查不到就明写未收到，不做本地回退，
 * 于是包没发、维度对不上、handler 没登记都会在界面上显形。
 *
 * <p>参数文字直接取 NBT 标签的 SNBT 形式，显示层因此不需要第二份参数类型表。代价是文字带类型后缀，
 * seed 显示成 0L、scaleX 显示成 16.0d。
 *
 * <p>四族放进同一个组。DebugScreenOverlay 按组数对半劈决定左右列，四族拆成四组时那条边界会落在族
 * 中间，参数多的族与其余三族被分到两列；一个组必然同列，组的疏密由组内打包控制。
 *
 * <p>组内每族一行族名加系统 id，参数行按行宽最匀切：行数取贪心的最少行数，在该行数下用动态规划
 * 最小化各行宽的平方和。总宽固定时平方和最小等价于方差最小，而行数不变，所以变匀不加高。单个参数
 * 自己超过上限时独占一行，不从中间截断。
 *
 * <p>上限取半屏宽：entry 拿不到自己落在左列还是右列，而右列的行从 guiWidth 减行宽起画，取整屏宽
 * 会压到另一列上。
 *
 * <p>布局按选择实例与上限缓存，只在收到新的系统包或窗口尺寸变化时重算；display 只在渲染线程调用，
 * 缓存不需要同步。
 */
public class SystemsDebugEntry implements DebugScreenEntry {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "systems");

    /** 上一次算好的布局。 */
    private static LevelSelection cachedSelection;
    private static int cachedLimit = Integer.MIN_VALUE;
    private static List<String> cachedLines = List.of();

    @Override
    public void display(DebugScreenDisplayer displayer, Level serverOrClientLevel, LevelChunk clientChunk,
            LevelChunk serverChunk) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        LevelSelection selection = ClientSystems.get(level.dimension().identifier());
        if (selection == null) {
            displayer.addToGroup(ID, "systems: not received");
            return;
        }
        int limit = (Minecraft.getInstance().getWindow().getGuiScaledWidth() - 4) / 2;
        if (selection != cachedSelection || limit != cachedLimit) {
            cachedSelection = selection;
            cachedLimit = limit;
            cachedLines = layout(selection, limit);
        }
        displayer.addToGroup(ID, cachedLines);
    }

    private static List<String> layout(LevelSelection selection, int limit) {
        List<String> lines = new ArrayList<>();
        addFamily(lines, limit, "terrain", selection.terrain());
        addFamily(lines, limit, "biome", selection.biome());
        addFamily(lines, limit, "surface", selection.surface());
        addFamily(lines, limit, "carver", selection.carver());
        return lines;
    }

    /** 追加一族：族名加系统 id 一行，其后是按行宽最匀切出来的参数行。 */
    private static void addFamily(List<String> lines, int limit, String family,
            SystemSelection selection) {
        lines.add(family + ": " + selection.id());
        List<String> tokens = new ArrayList<>(selection.args().size());
        for (Map.Entry<String, Arg> entry : selection.args().entrySet()) {
            tokens.add(entry.getKey() + "=" + entry.getValue().value().getValue());
        }
        lines.addAll(balanced(tokens, limit));
    }

    /**
     * 参数行的切分：行数取贪心的最少行数，在该行数下最小化各行宽的平方和。
     *
     * <p>代价取平方和是因为总宽固定，平方和最小等价于方差最小，也就是各行最齐。约束是每行不超上限，
     * 单个 token 自己超限时允许它独占一行，不从中间截断。复杂度是行数乘 token 数的平方，33 个参数
     * 的规模下可以忽略。
     */
    private static List<String> balanced(List<String> tokens, int limit) {
        int n = tokens.size();
        if (n == 0) {
            return List.of();
        }
        int space = width(" ");
        long[] prefix = new long[n + 1];
        for (int i = 0; i < n; i++) {
            prefix[i + 1] = prefix[i] + width(tokens.get(i));
        }
        int rows = greedyRows(prefix, space, limit);
        double[][] cost = new double[n + 1][rows + 1];
        int[][] cut = new int[n + 1][rows + 1];
        for (double[] row : cost) {
            Arrays.fill(row, Double.POSITIVE_INFINITY);
        }
        cost[n][0] = 0.0;
        for (int i = n - 1; i >= 0; i--) {
            for (int row = 1; row <= rows; row++) {
                for (int j = i; j < n; j++) {
                    int w = lineWidth(prefix, space, i, j);
                    if (j > i && w > limit) {
                        break;
                    }
                    if (cost[j + 1][row - 1] == Double.POSITIVE_INFINITY) {
                        continue;
                    }
                    double total = (double) w * w + cost[j + 1][row - 1];
                    if (total < cost[i][row]) {
                        cost[i][row] = total;
                        cut[i][row] = j;
                    }
                }
            }
        }
        List<String> out = new ArrayList<>(rows);
        int i = 0;
        for (int row = rows; row > 0; row--) {
            int j = cut[i][row];
            out.add(String.join(" ", tokens.subList(i, j + 1)));
            i = j + 1;
        }
        return out;
    }

    /** 贪心最少行数。固定词序下贪心就是最少行数，它保证动态规划有解。 */
    private static int greedyRows(long[] prefix, int space, int limit) {
        int n = prefix.length - 1;
        int rows = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && lineWidth(prefix, space, i, j + 1) <= limit) {
                j++;
            }
            i = j + 1;
            rows++;
        }
        return rows;
    }

    private static int lineWidth(long[] prefix, int space, int from, int to) {
        return (int) (prefix[to + 1] - prefix[from]) + space * (to - from);
    }

    private static int width(String text) {
        return Minecraft.getInstance().font.width(text);
    }
}
