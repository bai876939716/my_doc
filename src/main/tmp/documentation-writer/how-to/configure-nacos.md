# 如何:接入 Nacos 配置中心

**适用场景**:把环境真值(模型凭据、明道云凭据、base-shop 地址等)从散落的 `.env` 收敛到 Nacos 集中管理。2026-11-21 起代码不再携带任何环境真值,缺配置 = 启动显式报错拒起(来源:`app/config.py` 注释)。

**前提**:能访问公司的 MSE Nacos 控制台;应用所在网络可连通 Nacos 的 **8848 和 9848** 两个端口(grpc 通道必须)。

## 步骤

1. **在 Nacos 控制台建配置**

   - Data ID:`agent-server-center.yaml`(默认值,可用 `NACOS_DATA_ID` 覆盖)
   - Group:`AGENT_GROUP`(默认值,可用 `NACOS_GROUP` 覆盖)
   - 格式:YAML,**扁平键值、只允许「大写键: 标量」**,不写注释(`app/core/nacos.py` 启动时校验,违规拒起)

   仓库内有现成模板可整段粘贴:`docs/nacos-agent-server-center.yaml`。

2. **在应用侧只配引导参数**(`.env` 或环境变量)

   ```bash
   NACOS_SERVER_ADDR=<host>:8848
   # MSE 鉴权(三选一,按实例形态):
   NACOS_ACCESS_KEY=... / NACOS_SECRET_KEY=...     # MSE 鉴权
   # 或 NACOS_USERNAME=... / NACOS_PASSWORD=...    # 实例用户名密码
   ```

   `NACOS_*` 引导参数本身只在 `.env`/环境变量,**不进 Nacos**。

3. **重启服务**,日志出现:

   ```
   Nacos 配置读取完成:data_id=agent-server-center.yaml group=AGENT_GROUP,共 N 项
   ```

4. **验证**:`/readyz` 返回的 `llm_model`、`real_order_enabled` 应与 Nacos 一致。

## 优先级与生效

加载优先级恒为:**环境变量 > Nacos > .env > 代码默认值**(来源:`app/config.py` 文件头)。本机临时覆盖某项(如 `BASE_SHOP_URL` 指向本地代理)直接 `export` 即可,不必动 Nacos。任何修改都要**重启生效**——配置只在启动读一次。

## 常见问题

**启动报配置格式错误?** 检查控制台里是否混入了注释行、嵌套结构或小写键——只接受大写键 + 标量。

**连不上?** 先测 8848/9848 双端口连通性;客户端两个端口都要通,缺 grpc 通道(9848)会挂。

**PG_DSN 要不要进 Nacos?** 有意不进:只在 `CHECKPOINT_MODE=postgres` 时加到 `.env`(见 `docs/nacos-agent-server-center.yaml` 尾注)。
