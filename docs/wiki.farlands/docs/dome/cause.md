# 成因

Last Update: 2026/10/07

## 1. 前提

`CwgNoiseSystem` 构造器中 `foldCoordinates = false`，即 `farLands = true`。（[CwgNoiseSystem.java#L109](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoiseSystem.java#L109)）

若折叠打开，坐标被 `fold` 折回 $\pm 2^{30}$ ，饱和永不发生，穹顶随即消失。因此 `foldCoordinates = false` 是基本前提。

## 2. 直接成因

记 $\text{MAX} = 2147483647, \text{MIN} = -2147483648$ 。

[CwgNoise.java#L121-127](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoise.java#L121-127)

Java 的 `(int)` 对 double 是饱和。

| | 条件 | `(int)F` | 分支 | $x_0$ | $x_1=x_0+1$ |
|---|---|---|---|---|---|
| 正 | $F\ge \text{MAX}$ | $\text{MAX}$ | `F > 0`，值为 `(int)F` | $\text{MAX}$ | $\text{MAX}+1=\text{MIN}$ |
| 负 | $F\le \text{MIN}$ | $\text{MIN}$ | `F ≤ 0`，值为 `(int)F − 1` | $\text{MIN} - 1 = \text{MAX}$ | $\text{MAX}+1=\text{MIN}$ |

因此，一旦 $F\ge \text{MAX}$ 或 $F\le \text{MIN}$ ，两个格点索引 $x_0, x_1$ 就固定为 $\text{MAX}, \text{MIN}$ ，与坐标无关。

## 3. 权重溢出

### 正常情形 $u \in [0,1)$

$x_0 = \lfloor F\rfloor$ （[CwgNoise.java#L128-131](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoise.java#L128-131)）， $u:=F-x_0$ 是小数部分，落在 $[0,1)$ 。唯一的例外是 $F$ 为非正整数，0 也在内：此时代码走 `(int)F − 1` 分支， $x_0=F-1$ 、 $u=1$ ，两个权重变成 $(0,1)$ 。

$S(u)=u^2(3-2u)$ （[CwgNoise.java#L160-163](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoise.java#L160-163)）是标准 smoothstep。

$S(0)=0$ 、 $S(1)=1$ 、两端斜率为 0。所以 $S(u)$ 在 $[0,1]$ 内，权重也在 $[0,1]$ 内。

### 越界情形 $u \notin [0,1)$

索引冻结与权重越界是两个门槛。正侧索引从 $F=\text{MAX}$ 起就已固定，但那时 $u\in[0,1)$ ，权重还在正常区间，要到 $F=2^{31}$ 才有 $u=1$ ；负侧从 $F=\text{MIN}$ 起就直接落到 $u=-(2^{32}-1)$ 。

记最高阶的每方块缩放为 $s_{\text{ref}}$ 、最高阶号为 $k_{\max}$ ，则

$$\sigma_k=s_{\text{ref}}~2^{~k-k_{\max}}$$

越界之后 $x_0 = \text{MAX}$ ，此时 $u = F - \text{MAX}$ 变为越界量，所以 $F$ 与 $u$ 同时递增 $\sigma_k$ 。

### 带入设计域外 S

$$S(u)=3u^2-2u^3,\qquad 1-S(u)=1-3u^2+2u^3$$

当 $|u|\gg1$ 时

$$|2u^3| \gg |3u^2| \gg 1$$

于是两个权重是

$$1-S(u)=+2u^3-3u^2+1,\qquad S(u)=-2u^3+3u^2$$

**两个特性**：

1. $(+2u^3-3u^2+1)+(-2u^3+3u^2)=1$ ，两个权重仍构成 partition of unity，和为 1；但两者都已离开 $[0,1]$ ，此时不再是插值。
2. 可以改为统一写法。设每个轴一个标量 $W = 2u^3 - 3u^2$ ，于是

$$w_i(\kappa_i)=\varepsilon_{\kappa_i}W_i+\delta_{\kappa_i,0}$$

$$\varepsilon_0=+1,\qquad \varepsilon_1=-1,\qquad \delta_{\kappa_i,0}=(\kappa_i=0)$$

## 4. 角点溢出

### 仿射

用 $\kappa = (\kappa_x, \kappa_y, \kappa_z) \in \{0, 1\}^3$ 记代码中的八次 `gradient` 调用。

（[CwgNoise.java#L132-134](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoise.java#L132-134), [CwgNoise.java#L135-137](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoise.java#L135-137), [CwgNoise.java#L139-141](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoise.java#L139-141), [CwgNoise.java#L142-144](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoise.java#L142-144)）

自 [**CwgNoise.java#L150-159**](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoise.java#L150-159)：

| 记号 | 代码 | 值 |
|---|---|---|
| $\mathbf F$ | `fx, fy, fz` | 采样点，即缩放后的坐标 |
| $\mathbf i_\kappa$ | `ix, iy, iz` | 该角点的格点索引，整数 |
| $\mathbf G_\kappa$ | `GRADIENTS[...]` | 该角点的梯度，三个常数分量 |

即**公式**：

$$g_\kappa=\mathbf G_\kappa\cdot\bigl(\mathbf F-\mathbf i_\kappa\bigr)+0.5$$

- $\mathbf G_\kappa$ 由**格点索引**和**该阶种子**通过哈希决定，因此它是常数。
- $\mathbf i_\kappa$ 也是常数。

因此该公式对 $\mathbf F$ 是一次函数，即仿射。

**正常情形**： $\mathbf i_{\kappa_i=0}=\lfloor F_i\rfloor$ ， $\mathbf i_{\kappa_i=1}=\lfloor F_i\rfloor+1$ ，所以两个位置向量只差 $1$ ：

$$F-i_0=u\in[0,1),\qquad F-i_1=u-1\in[-1,0)$$

**冻结情形**： $i_0$ 固定为 $\text{MAX}$ ，因此 $i_1=i_0+1$ 回绕为 $\text{MIN}$ 。于是

$$i_0-i_1=\bigl(2^{31}-1\bigr)-\bigl(-2^{31}\bigr)=2^{32}-1$$

可统一为：

$$F_i-i_{\kappa_i}=u_i+\kappa_i\bigl(2^{32}-1\bigr)$$

### 代入混合式，提取公因子

一个格点的份数是三个因子相乘：

$$w_\kappa=\prod_{i\in\{x,y,z\}}\bigl(\varepsilon_{\kappa_i}W_i+\delta_{\kappa_i,0}\bigr)$$

#### 乘开

每个因子有两块可选择： $\varepsilon_{\kappa_i}W_i$ 与 $\delta_{\kappa_i,0}$ 。

展开后的八个乘积项与轴集合 $\Lambda\subseteq\{x,y,z\}$ 逐项对应， $\Lambda$ 内的轴取 $\delta$ 项、 $\Lambda$ 外的轴取 $\varepsilon W$ 项。

$$w_\kappa=\Bigl(\prod_{i}\varepsilon_{\kappa_i}\Bigr)\Bigl(\prod_{i}W_i\Bigr)+\sum_{\varnothing\ne \Lambda\subseteq\{x,y,z\}}\Bigl(\prod_{i\in \Lambda}\delta_{\kappa_i,0}\Bigr)\Bigl(\prod_{i\notin \Lambda}\varepsilon_{\kappa_i}W_i\Bigr)$$

注意到 $\prod_iW_i=W_xW_yW_z$ 、 $\prod_i\varepsilon_{\kappa_i}=\sigma_\kappa$ ，并记

$$T_\Lambda(\kappa)=\Bigl(\prod_{i\in \Lambda}\delta_{\kappa_i,0}\Bigr)\Bigl(\prod_{i\notin \Lambda}\varepsilon_{\kappa_i}W_i\Bigr)$$

则

$$w_\kappa=\sigma_\kappa W_xW_yW_z+\sum_{\varnothing\ne \Lambda}T_\Lambda(\kappa)$$

#### 混合式

$$C=\sum_{\kappa\in\{0,1\}^3}\Bigl[\prod_{i\in\{x,y,z\}}w_i(\kappa_i)\Bigr]g_\kappa$$

**代入**：

$$C=\sum_{\kappa}\Bigl[\sigma_\kappa W_xW_yW_z+\sum_{\varnothing\ne \Lambda}T_\Lambda(\kappa)\Bigr]g_\kappa$$

$$C=\sum_{\kappa}\sigma_\kappa W_xW_yW_z~g_\kappa+\sum_{\kappa}\Bigl[\sum_{\varnothing\ne \Lambda}T_\Lambda(\kappa)\Bigr]g_\kappa$$

$W_xW_yW_z$ 与 $\kappa$ **无关**，于是：

$$\sum_{\kappa}\sigma_\kappa W_xW_yW_z~g_\kappa=W_xW_yW_z\cdot\sum_{\kappa}\sigma_\kappa g_\kappa$$

**命名**：

$$C=W_xW_yW_z\underbrace{\sum_{\kappa}\sigma_\kappa g_\kappa}_{=:\ \ell(\mathbf F)}+\underbrace{\sum_{\kappa}\Bigl[\sum_{\varnothing\ne \Lambda}T_\Lambda(\kappa)\Bigr]g_\kappa}_{=:\ R}$$

**即**：

$$C=W_xW_yW_z~\ell(\mathbf F)+R$$

#### $R$ 的量级

每根取了 $\delta$ 的轴，少乘一个 $W_i$ ；而 $|u_i|\ge3$ 时 $|W_i|=|u_i|^2|2u_i-3|\ge|u_i|^3$ ，所以

$$\frac{|T_\Lambda(\kappa)|}{\prod_i|W_i|}\ \le\ \prod_{i\in \Lambda}\frac{1}{|u_i|^3}$$

**相对量级**：

$$\eta:=4\sum_i\frac{1}{|u_i|^3}+2\sum_{i \lt j}\frac{1}{|u_i|^3|u_j|^3}+\prod_i\frac{1}{|u_i|^3}$$

系数来自对 $\kappa$ 的求和：固定 $\Lambda$ 后只钉住 $\Lambda$ 内的位，存活的 $\kappa$ 有 $2^{3-|\Lambda|}$ 个，所以 $|\Lambda|=1,2,3$ 分别带 $4,2,1$ 倍。

$\eta$ 是 $R$ 相对公共因子 $W_xW_yW_z$ 的量级，不是 $R$ 相对第一块 $W_xW_yW_z~\ell$ 的比值：第一块在 $\ell=0$ 上恒为零，那个比值在零集上不存在。写成

$$\frac{C}{W_xW_yW_z}=\ell(\mathbf F)+\rho(\mathbf F),\qquad |\rho|\le\eta\cdot\max_\kappa|g_\kappa|$$

则零集是 $\ell+\rho=0$ 。在 $\nabla\ell\ne0$ 时，它在 $\mathbf F$ 空间里到平面 $\ell=0$ 的距离不超过 $|\rho|/|\nabla\ell|$ ；深区里 $\eta=O(u^{-3})$ 、 $\max_\kappa|g_\kappa|=O(u)$ ，故 $\rho=O(u^{-2})$ 并随 $|u_i|$ 增大趋于零。

### 零集

$\ell=\sum_\kappa\sigma_\kappa g_\kappa$ ： $\sigma_\kappa$ 是常数， $g_\kappa$ 仿射，仿射函数的常系数和仍是仿射。即

$$\ell(\mathbf F)=\alpha_xF_x+\alpha_yF_y+\alpha_zF_z+\beta$$

$\ell=0$ 在三维里是平面。 $R$ 的量级可忽略，所以真实零集渐近收敛于这张平面。退化条件： $\nabla\ell=(\alpha_x,\alpha_y,\alpha_z)\ne0$ ，即 $(a,b,c)\ne(0,0,0)$ ；三者同时为零时 $\ell$ 是常数， $\beta\ne0$ 时零集为空， $\beta=0$ 时领头项整体消失、由 $R$ 主导。

### $a, b, c, d$

#### 代入并分组

把 $g_\kappa$ 代进 $\ell$ ：

$$\ell=\sum_{i}\Bigl[\sum_{\kappa}\sigma_\kappa G_{\kappa,i}\Bigr]F_i-\sum_{i}\sum_{\kappa}\sigma_\kappa G_{\kappa,i}~i_{\kappa_i}+\frac12\sum_{\kappa}\sigma_\kappa$$

#### $\tfrac12$ 项消失

$$\sum_{\kappa}\sigma_\kappa=\prod_{i\in\{x,y,z\}}\bigl(\varepsilon_0+\varepsilon_1\bigr)=(1-1)^3=0$$

所以 $\ell$ 展开式的第三项为零，常数项里不含 $+\frac12$ 。

#### 法向系数

$\ell$ 展开式第一项的括号记作

$$\alpha_i:=\sum_{\kappa}\sigma_\kappa G_{\kappa,i}$$

乘 2 存成整数即

$$a=2\alpha_x,\qquad b=2\alpha_y,\qquad c=2\alpha_z$$

带的是三重积符号 $\sigma_\kappa=\varepsilon_x\varepsilon_y\varepsilon_z$ 。

#### 常数项

冻结时两个索引相差 $2^{32}-1$ ，于是

$$i_{\kappa_i}=i_0-\kappa_i\bigl(2^{32}-1\bigr),\qquad i_0=\text{MAX}$$

代入 $\ell$ 展开式的第二项：

$$\beta=-\sum_{i}\sum_{\kappa}\sigma_\kappa G_{\kappa,i}~i_{\kappa_i}=-i_0\sum_i\alpha_i+\bigl(2^{32}-1\bigr)\sum_iQ_i$$

$$Q_i:=\sum_{\kappa}\sigma_\kappa\kappa_i G_{\kappa,i}$$

#### $d$ 的来历

由 $\kappa_x=(1-\varepsilon_x)/2$ ：

$$\sigma_\kappa\kappa_x=\varepsilon_x\varepsilon_y\varepsilon_z\cdot\frac{1-\varepsilon_x}{2}=\frac{\sigma_\kappa-\varepsilon_y\varepsilon_z}{2}$$

两边乘 $G_{\kappa,x}$ 再对 $\kappa$ 求和：

$$Q_x=\frac{\alpha_x-P_x}{2},\qquad P_x:=\sum_{\kappa}\varepsilon_y\varepsilon_zG_{\kappa,x}$$

$y$ 、 $z$ 轮换同理。定义

$$d:=-2\bigl(P_x+P_y+P_z\bigr)$$

即

$$d=-2\Bigl[\sum_\kappa\varepsilon_y\varepsilon_zG_{\kappa,x}+\sum_\kappa\varepsilon_z\varepsilon_xG_{\kappa,y}+\sum_\kappa\varepsilon_x\varepsilon_yG_{\kappa,z}\Bigr]$$

#### 合并

由定义

$$\sum_i\alpha_i=\frac{a+b+c}{2},\qquad \sum_iP_i=-\frac d2$$

代入常数项：

$$\beta=-i_0\cdot\frac{a+b+c}{2}+\frac{2^{32}-1}{2}\Bigl(\frac{a+b+c}{2}+\frac d2\Bigr)=\frac{a+b+c}{2}\Bigl[\frac{2^{32}-1}{2}-i_0\Bigr]+\frac{2^{32}-1}{4}d$$

括号里的差化整：

$$\frac{2^{32}-1}{2}-i_0=\frac{2^{32}-1}{2}-\bigl(2^{31}-1\bigr)=\frac12$$

#### 结果

$$\beta=\frac{a+b+c}{4}+\frac{2^{32}-1}{4}d$$

于是

$$\ell=\frac{a}{2}F_x+\frac{b}{2}F_y+\frac{c}{2}F_z+\frac{a+b+c}{4}+\frac{2^{32}-1}{4}d$$

## 5. 方程

### 坐标

`sample` 先把方块坐标乘上基准频率（[CwgNoise.java#L85-88](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoise.java#L85-L88)），之后每进一阶，把这三个缩放坐标再各乘 2（[CwgNoise.java#L95-98](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoise.java#L95-L98)）。Y 的基准频率是 XZ 的一半（[CwgNoiseSystem.java#L44-45](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoiseSystem.java#L44-L45)）。

于是

$$F_x=\sigma_kx,\qquad F_y=\frac{\sigma_k}{2}y,\qquad F_z=\sigma_kz$$

代入 $\ell=0$ ，整体除以 $\frac{\sigma_k}{2}$ ，则

$$a~x+\frac{b}{2}y+c~z+\frac{a+b+c}{2\sigma_k}+\frac{2^{32}-1}{2\sigma_k}d=0$$

### $T_k$

$\frac{2^{32}-1}{2\sigma_k}$ 这一项即门槛：

$$\frac{2^{32}-1}{2\sigma_k}=\frac{2^{31}}{\sigma_k}\Bigl(1-2^{-32}\Bigr)$$

而 $\frac{2^{31}}{\sigma_k}$ 只依赖阶号。

于是

$$\frac{2^{31}}{\sigma_k}=\frac{2^{31}}{s_{\text{ref}}}~2^{~k_{\max}-k}=2^{~k_{\max}-k}E,\qquad E:=\frac{2^{31}}{s_{\text{ref}}}$$

记 $T_k:=2^{~k_{\max}-k}E$ ，平面方程即

$$a~x+\frac{b}{2}y+c~z+T_k\bigl(1-2^{-32}\bigr)d+\frac{a+b+c}{2\sigma_k}=0$$

### 方块距离公式

真实零集 $\ell+\rho=0$ 与平面 $\ell=0$ 在方块坐标里的间距是

$$\text{dist}_{\text{block}}=\frac{2|\rho|}{\sigma_k\sqrt{a^2+\frac{b^2}{4}+c^2}}$$

两步得到： $\ell$ 在 $\mathbf F$ 空间的梯度是 $(\alpha_x,\alpha_y,\alpha_z)$，点到平面的距离是 $|\rho|/|\nabla\ell|$ ；换到方块坐标后各轴缩放不同，梯度变成 $\bigl(\sigma_k\alpha_x,\ \frac{\sigma_k}{2}\alpha_y,\ \sigma_k\alpha_z\bigr)$ ，按 $a=2\alpha_x$、 $b=2\alpha_y$、 $c=2\alpha_z$ 代掉即得。

代入 $|\rho|\le\eta\cdot\max_\kappa|g_\kappa|$ 得它的上界

$$\text{dist}_{\text{block}}\le\frac{2~\eta~\max_\kappa|g_\kappa|}{\sigma_k\sqrt{a^2+\frac{b^2}{4}+c^2}}$$

### 可见性

#### 候选面

$a,b,c,d$ 由 $\sum_\kappa\sigma_\kappa G_{\kappa,i}$ 这类和决定，而 $\mathbf G_\kappa$ 由格点索引与该阶种子通过哈希决定。记该族的基础 int seed 为 $\text{base}$ （[CwgNoiseSystem.java#L120-127](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoiseSystem.java#L120-L127)），该阶种子是

$$\text{seedEff}=\text{base}+k$$

[CwgNoise.java#L90-95](../../../../src/main/java/com/inf/farlands/terrain/system/terrain/noise/overworld/Cwg/CwgNoise.java#L90-L95)

所以每一阶各有一组 $(a,b,c,d)$ ，也就各有一张面。`CwgNoiseSystem` 建了四个噪声，`low` 与 `high` 各 16 阶、`selector` 8 阶，共 40 组候选。`depth` 的 Y 频率为 0，Y 永不冻结，不在候选面。

#### 八分体与符号

两个方向的索引都落到同一对 $\text{MAX}$ 与 $\text{MIN}$ ，因此 $(a,b,c,d)$ 与坐标落在哪个符号侧无关，八个八分体共用同一组系数， $\ell=0$ 在全局坐标里是同一张平面。随象限变化的是公因子 $W_xW_yW_z$ 的符号：在 $|u_i|\gg1$ 的区间里 $W_i$ 与 $u_i$ 同号，于是实体侧按象限奇偶翻转。

#### 可达性

三轴都越界， $W_xW_yW_z$ 才提得出来： $|x|,|z|\ge T_k$ 、 $|y|\ge 2T_k$ 。门槛最大的那条轴决定能不能落进坐标范围。记它的相对频率为 $h$ ，Y 取 $h=\frac12$ ，条件即

$$\frac{T_k}{h}\le \text{MAX}$$

代 $T_k=2^{~k_{\max}-k}E$ 得

$$k\ \ge\ k_{\max}-\Bigl\lfloor\log_2\frac{h\text{MAX}}{E}\Bigr\rfloor$$

门槛不够小的阶进不了顶点区，平面自身的落点也可能越出 $|F|\le \text{MAX}$ 。

#### 主导

写进地形的是各阶之和，不是某一阶。某一阶的零面只是该阶为零之处，那里的符号由其余阶决定。
