# z-observer 部署清单（deploy/k8s）

本目录是 **z-observer** 在家庭 k3s 集群（节点 `zifang001` / `zifang002`）的部署清单。

这批 Deployment 当初是手工 `kubectl apply` 上去的，仓里查不到「它是怎么部署的」——
集群无法从代码复现，发版只能靠记忆和手记。本目录把**线上实况**（`kubectl get deploy,svc -o yaml` 的真实导出）固化回代码。

> ⚠ 本目录**只产出清单，不代跑**。`kubectl apply` 会改动生产集群，需要人工确认后执行。

## 0. 清单内容

| 文件 | 对象 | 线上对应 |
|---|---|---|
| `00-namespace.yaml` | Namespace `z-observer` | 已存在 |
| `01-service.yaml` | Service `z-observer-admin`（NodePort：31891 / 31430 / 31910） | 已存在 |
| `02-deployment.yaml` | Deployment `z-observer-admin`（镜像 `zopc/z-observer:local`，replicas 1） | 已存在 |
| `README.md` | 本文件 | — |

线上实况要点（与基线导出逐条对齐）：

- Deployment 名 `z-observer-admin`，容器名 `z-observer`
- 容器端口 **三个**：`http 8080` / `collector 14300` / `metrics 9100`（端口名照抄）
- Service `z-observer-admin`，`type: NodePort`：
  `http 8080→http:31891`、`collector 14300→collector:31430`、`metrics 9100→metrics:31910`
- 镜像 `zopc/z-observer:local`，`imagePullPolicy: Never`
- env：`JAVA_OPTS=-Xms256m -Xmx1024m`
- resources：requests `200m` / `1280Mi`，limits `2` CPU / `2Gi`
- 本域**无 PVC**；**有两个鉴权 Secret**（见 §3.0，鉴权改造后新增）

⚠ **本域的 `/actuator/health` 是 404**（本仓没暴露 actuator 端点）。
套通用 actuator 探针会得到「永远失败」⇒ Pod 永远不就绪。
探针必须用 **`/api/internal/health`**（免鉴权的内部健康端点，只答活着不活着）。

## 1. 怎么构建镜像

### 现状（如实说明）

**本仓没有 Dockerfile。** 全仓 grep 无 `Dockerfile` / `*.Dockerfile`（`z-observer/deploy/` 目录是本任务新建的）。
⇒ **「把源码变成 `zopc/z-observer:local`」这一步目前不在仓内**，
线上那份镜像是当初在节点上手工构建后喂进 containerd 的，仓里没有留下可复现的构建定义。

线上镜像口径：裸 tag `zopc/z-observer:local`，只存在于两个节点的 containerd，不在任何 registry。

### 建议命令（可执行；Dockerfile 需先补进本仓）

```bash
# 1) 打 jar —— 启动类是 z-observer-admin 里的 ObserverAdminApplication
#    （本仓 z-observer-admin 声明了 spring-boot-maven-plugin ⇒ 产出的是可执行 boot jar）
cd z-observer
mvn -pl z-observer-admin -am clean package -DskipTests
# 产物：z-observer-admin/target/z-observer-admin.jar
#    （该模块的 finalName 不带版本号，jar 名就是 z-observer-admin.jar）

# 2) 本仓当前没有 Dockerfile，建议新增 deploy/Dockerfile（Java 8 口径与本仓父链一致；
#    参考 z-task/Dockerfile 的写法）：
#
#    FROM eclipse-temurin:8-jre
#    WORKDIR /app
#    COPY z-observer-admin/target/z-observer-admin.jar app.jar
#    ENV SERVER_PORT=8080
#    EXPOSE 8080 14300 9100
#    ENTRYPOINT ["java", "-jar", "app.jar"]

# 3) 构建（tag 必须与 02-deployment.yaml 里的 image: 逐字一致）
docker build -t zopc/z-observer:local -f deploy/Dockerfile .
```

## 2. 怎么喂 containerd

铁律三条：

1. **裸 tag，禁 Nexus 前缀**——本集群 `imagePullPolicy: Never`，k3s 不会去 registry 拉，
   它按 `image:` 的字面引用去节点本地 content store 找。喂进去的 tag 带前缀
   （如 `nexus.internal/zopc/z-observer:local`）而清单里写的是裸 tag（或反过来）⇒ **ErrImageNeverPull**。
2. **必须用 `cat` 管道喂 `k3s ctr images import -`**——`ctr images import` 从 **stdin** 读，
   直接把文件路径当参数会报 `unrecognized image format`。
3. **两个节点都要喂**——两个节点都是工作节点，Pod 可能落在任一节点；只喂一个会出现
   `ErrImageNeverPull` + Pod 反复重建。

```bash
# 1) 在开发机上导出镜像（build 可在开发机做，不必上节点）
docker save zopc/z-observer:local > /tmp/z-observer-local.tar
scp /tmp/z-observer-local.tar zifang@192.168.31.231:/tmp/     # zifang001
scp /tmp/z-observer-local.tar zifang@192.168.31.136:/tmp/     # zifang002

# 2) 喂 zifang001（192.168.31.231）——该节点 sudo 需要口令，会交互提示
ssh zifang@192.168.31.231 'cat /tmp/z-observer-local.tar | sudo k3s ctr images import -'

# 3) 喂 zifang002（192.168.31.136）——该节点 sudo -n 可免密
ssh zifang@192.168.31.136 'cat /tmp/z-observer-local.tar | sudo -n k3s ctr images import -'

# 4) 核验（两个节点都要看到，且 tag 必须与清单一致）
ssh zifang@192.168.31.231 'sudo k3s ctr images ls | grep z-observer'
ssh zifang@192.168.31.136 'sudo -n k3s ctr images ls | grep z-observer'
```

一步到位的写法（不落临时文件，效果等价）：

```bash
docker save zopc/z-observer:local | ssh zifang@192.168.31.231 'cat - | sudo k3s ctr images import -'
docker save zopc/z-observer:local | ssh zifang@192.168.31.136 'cat - | sudo -n k3s ctr images import -'
```

## 3. 怎么 apply

### 3.0 ⚠ 前置：先建两个鉴权 Secret（顺序是硬前提）

卡 `TASK-20261022-017` 第 1/4 项给本域加了两道闸门，两道都是 **fail closed**
（缺密钥 ⇒ 进程**启动失败**并指名道姓说缺哪个环境变量）：

| Secret | 键 | 保护什么 | 缺了会怎样 |
|---|---|---|---|
| `z-observer-admin-auth` | `token` | 管理面 8080（NodePort 31891）的**全部** HTTP 面：读端点 + `POST /api/rules` / `/api/silences` / `/api/inhibits` 三个写端点 | 进程起不来 |
| `z-observer-collector-auth` | `token` | 采集口 14300（NodePort 31430）的**客户端身份**（共享密钥 + HMAC 握手 + 拒重放） | 进程起不来 |

**值不进仓**：从密钥库取（或本地一次性生成）后直接灌进集群。

```bash
# ⚠ 会改动生产集群（写 secret）⇒ 需要人工确认后执行；本仓只产出清单，不代跑。
# 值从环境变量/密钥库取，**不要**写进命令历史或仓库：
kubectl -n z-observer create secret generic z-observer-admin-auth \
  --from-literal=token="$Z_OBSERVER_ADMIN_AUTH_TOKEN" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n z-observer create secret generic z-observer-collector-auth \
  --from-literal=token="$Z_OBSERVER_COLLECTOR_TOKEN" \
  --dry-run=client -o yaml | kubectl apply -f -
```

**顺序铁律**：Secret → 镜像喂进节点 → apply Deployment。
（Secret 必须在 Deployment 之前：鉴权 fail closed，Secret 没建就 apply ⇒ Pod CrashLoopBackOff。）

### 3.1 apply

```bash
# ⚠ 会改动生产集群，需要人工确认后再执行；本仓只产出清单，不代跑。
kubectl apply -f deploy/k8s/
```

执行顺序与前置条件（apply 前置检查）：

1. **namespace 先在**：`00-namespace.yaml` 已包含在目录里，`kubectl apply -f deploy/k8s/`
   会按文件名顺序（00 → 01 → 02）依次 apply，namespace 自动先建。
2. **Secret 先在**：见 §3.0（两个鉴权 Secret）。
3. **镜像先在**：见 §2。两个节点都喂完再 apply，否则 Pod 起不来。
4. 本域**无 PVC**，不需要额外前置对象。
5. **采集链路是别人的上游**：本域的 `collector 14300` / `metrics 9100` 会被别的采集端
   连（`z-observer` 是被观测端）。滚动更新期间会有几秒断连，属预期。
   ⚠ 采集口开了客户端鉴权后，**没配共享密钥的采集端会被断连**
   ⇒ 发版前先确认调用方已配上 `z.observer.transport.auth-token`。

apply 后自检：

```bash
kubectl -n z-observer get deploy,svc,pods
kubectl -n z-observer describe deploy/z-observer-admin | tail -30
# 探针端点自检（本域 /actuator/health 是 404，别拿它判断）
kubectl -n z-observer exec deploy/z-observer-admin -- wget -q -O- http://127.0.0.1:8080/api/internal/health
# 鉴权是否真的生效（匿名读 ⇒ 必须 401）
kubectl -n z-observer exec deploy/z-observer-admin -- \
  wget -q -S -O- http://127.0.0.1:8080/api/rules 2>&1 | head -3
```

## 3.2 暴露面收口（卡第 3 项 · **待人工决策**，本仓**未 apply**）

本域三个 NodePort 端口的收口建议与影响面分析。**结论先写清：这是建议，不是已执行的动作。**

| 端口 | 现状（实测） | 建议 | 影响面 |
|---|---|---|---|
| **31891** admin | 两节点可达；6 条真实 API 路径匿名可读；3 个写端点匿名可写 | **NodePort → ClusterIP** | **全组织代码里搜不到任何对 31891 的引用**（只有本仓清单与巡检报告提到它）⇒ 无已知消费者。改后只能集群内访问（`z-observer-admin:8080`） |
| **31430** collector | 两节点可达；协议只校验 magic、零客户端身份校验 | 保留 NodePort，但**必须**先有客户端鉴权（本卡第 4 项已实现，见 §3.0 的第二个 Secret） | 当前**采集端 0 帧**、全组织无 `z.observer.transport.host` 指向它 ⇒ 现在没有真实消费者；但它是 SDK 的入口，撤了会让将来接 SDK 的人找不到路 |
| **31910** metrics | **两节点都 RST**（9100 无进程监听，容器端口声明了但没起） | **删掉这个端口**（Service 的 `metrics` 端口条目 + Deployment 的 `metrics` 容器端口） | 无人监听 ⇒ 删掉不影响任何东西；留着只是让人以为「metrics 抓取口通着」 |

**为什么标「待人工决策」**：NodePort → ClusterIP 属于**改生产暴露面**的集群写操作，
且要确认「确实没有集群外消费者」（本仓只能证明**代码里没有引用**，
不能证明「有人用 curl/Postman 手工打」）。⇒ 需要用户拍板后再由 z-ops 派发执行。

**清单改动写在哪**：**故意不写进 `01-service.yaml`** ——
同一个目录里放两份 Service 清单，`kubectl apply -f deploy/k8s/` 会把两份都 apply，
等于把「待决策」变成「已执行」。⇒ 收口动作由 z-ops 派发时按上面的表逐条改。

## 4. 怎么回滚

```bash
# 看历史版本
kubectl -n z-observer rollout history deploy/z-observer-admin

# 回滚到上一个版本
kubectl -n z-observer rollout undo deploy/z-observer-admin

# 回滚到指定版本
kubectl -n z-observer rollout undo deploy/z-observer-admin --to-revision=<N>

# 看回滚是否完成
kubectl -n z-observer rollout status deploy/z-observer-admin
```

⚠ **PVC 数据不会自动回滚**：本域没有 PVC（纯无状态 Deployment），
但 z-observer 用的存储若在共享 RDS 上，回滚 Pod **不会**回滚数据库 schema / 数据。
镜像里带 DDL 变更时，回滚镜像后旧 schema 仍在，需要人工确认兼容性。

⚠ **回滚的前提是节点上有旧镜像**：回滚只改 Deployment 里的 `image:` 字段指向的旧 tag，
该 tag 必须仍在这两个节点的 containerd 里（`k3s ctr images ls` 能看到）。旧镜像被清掉就回滚不动。

## 5. 本清单与线上的差异（抄写时发现）

| 项 | 线上 | 本清单 | 原因 |
|---|---|---|---|
| readinessProbe | `httpGet /api/traces`（链路查询页） | `httpGet /api/internal/health`（readiness + liveness） | **鉴权改造后（卡第 1 项）管理面整条挡上**，k8s 探针不带 Authorization 头 ⇒ 业务查询面会拿到 401 ⇒ Pod 永远不就绪。⇒ 换成专门开的免鉴权内部健康端点（只答活着不活着，不带业务数据）。另：本域 `/actuator/health` 是 **404**，通用 actuator 探针不可用 |
| env | 只有 `JAVA_OPTS` | 加 `Z_OBSERVER_ADMIN_AUTH_TOKEN` / `Z_OBSERVER_COLLECTOR_TOKEN`（均来自 Secret） | 卡第 1/4 项的入站鉴权与采集口客户端鉴权；两者都 fail closed（缺密钥 ⇒ 启动失败） |
| Service 端口 | `http:31891` / `collector:31430` / `metrics:31910` 三个 NodePort | 暂**照抄不改** | 收口（admin → ClusterIP、metrics 端口删除）是**改生产暴露面**的集群写操作 ⇒ 待人工决策，见 §3.2 |
| labels | 资源上只有 `app.kubernetes.io/name` | 额外加 `app.kubernetes.io/part-of` | 仅加标签，不影响 selector 匹配；`apply` 是幂等合并 |

## 6. 待办（不在本任务范围内）

- 本仓**缺 Dockerfile**，镜像构建不可复现（§1 已给出建议内容，需人工补进仓并提交）。
- 本域没暴露 actuator 端点（`/actuator/health` 404）。若要在别处复用统一探针口径，
  需要在应用侧补一个 actuator 端点，而不是在各仓清单里各写各的。
- **暴露面收口待人工决策**（§3.2）：admin 31891 → ClusterIP、metrics 31910 端口删除。
  本仓只给建议与影响面，**清单故意不改**（同一目录放两份 Service 清单会被 `apply -f` 一起 apply）。
- **采集口的共享密钥分发**：收端从 `Z_OBSERVER_COLLECTOR_TOKEN` 取，
  SDK 侧从 `z.observer.transport.auth-token` 取（推荐环境变量 `Z_OBSERVER_TRANSPORT_AUTH_TOKEN`）。
  收端开了鉴权而 SDK 没配 ⇒ 连接被断 ⇒ 接入方必须先配上。
