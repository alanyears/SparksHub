# SparksHub - 本地生活点评平台

> 基于 Spring Boot + Vue.js 的本地生活服务点评平台，集成 Dify AI 工作流引擎，支持 AI 智能总结用户评论。

[![Java](https://img.shields.io/badge/Java-1.8-blue.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.3.12-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue.js](https://img.shields.io/badge/Vue.js-2.x-4fc08d.svg)](https://vuejs.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-orange.svg)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-7.x-red.svg)](https://redis.io/)
[![Dify](https://img.shields.io/badge/Dify-AI%20Workflow-6366f1.svg)](https://dify.ai/)

---

## 📖 项目简介
- 视频演示：https://www.bilibili.com/video/BV1NKjN6aEHL/?spm_id_from=333.1387.homepage.video_card.click&vd_source=fca15a1c9bfc53c1217a943d1fea2c3c
- **本项目仅用于学习演示目的，不公开全部代码，仅提供视频演示。**
- SparksHub是一个本地生活服务点评平台，用户可以浏览商户信息、发布探店笔记、参与评论互动。项目核心亮点是集成了 **Dify Agent 工作流引擎**，基于用户评论数据自动生成 **AI 智能总结**，帮助访客快速了解商户口碑。


## 📖 项目描述
- 作品简介：SparksHub 是一款模拟营销峰值治理引擎 POC。通过模拟本地生活平台的营销秒杀场景，从架构层面实现了一套“缓存层防护＋异步削峰＋一致性兜底”的可复用方案，为B端商家营销活动提供保障；同时引入 Dify 工作流，辅助C端用户访客智能总结点评笔记与评论，优化其使用体验。
- 技术栈：墨刀、Java、SpringBoot、MyBatis-plus、MySQL、Redis、Lua、Dify

### 问题需求：

(1) 点评与评论缺乏结构化提炼，需C端用户自行翻阅筛选，导致信息过载，拖慢用户决策的推进速度，增加 C端用户决策时间成本。

(2) 平台B端商户在进行整点大促等营销活动下瞬时高并发流量打到 DB，导致响应劣化，影响C端用户使用。

(3) 平台同步扣库存链路存在超卖风险，影响B端商家收益。



### 方案：

(1) AI智能总结方案：接入LLM对点评/评论做结构化摘要与情绪要点提炼；通过Dify将Prompt模板管理与调用逻辑解耦，业务侧可在画布上调优输出而无需改后端发版，另外配套输出校验与API不可用时的降级兜底机制。

(2) 流量稳定性治理：考虑业务需求和部署成本，引入 Redis Stream 作为消息队列，将秒杀请求与订单创建解耦，请求阶段完成库存校验与消息投递；制定多级缓存策略，采用逻辑过期与缓存空值方案拦截无效及突发并发请求。

(3) 交易一致性风控：采用“分布式锁 + Lua 脚本”保障预扣库存的原子性，配合 MySQL 乐观锁进行最终一致性校验，防止超卖，减少B端商家损失。



### 结果验证：
- AI总结工作流跑通 "数据→ Prompt 编排→结构化总结输出"闭环；同时经本地 JMeter 压测验证，项目在 500 并发下吞吐量达到 4000 TPS，平均响应时间由 480ms 缩短至 125ms，核心数据缓存命中率达 99.5%，测试环境下超卖率为 0。

### 核心功能

| 模块 | 功能描述 |
|------|---------|
| 🏪 **商户浏览** | 按分类浏览商户，查看商户详情、评分、地址等信息 |
| 📝 **探店笔记** | 用户发布图文笔记，分享探店体验 |
| 💬 **评论互动** | 用户对笔记进行评论，点赞互动 |
| 👥 **关注Feed** | 关注感兴趣的用户，查看关注流推送 |
| 🎫 **优惠券秒杀** | 商户发布限时优惠券，用户参与秒杀抢购 |
| 🤖 **AI 智能总结** | 基于 Dify + GLM-4 模型，自动总结评论口碑，附带来源追溯 |
| 👍 **反馈收集** | 用户对 AI 总结点赞/踩，收集偏好数据用于模型微调（SFT） |

### AI 总结工作流

```
用户点击"AI总结"
    → 后端查询数据库（博客内容 + 评论列表）
    → 发送至 Dify 工作流 API
    → Dify Code 节点清洗数据
    → LLM 节点（GLM-4-Flash）生成结构化总结
    → 结果缓存 Redis（24小时）
    → 前端弹窗展示
```

---

## 🛠️ 技术栈

### 后端
- **框架**: Spring Boot 2.3.12
- **ORM**: MyBatis-Plus 3.4.3
- **数据库**: MySQL 8.0
- **缓存**: Redis 7.x + Lettuce 连接池
- **消息队列**: RabbitMQ（秒杀异步处理）
- **分布式锁**: Redisson 3.13.6
- **工具库**: Hutool 5.7.17
- **AI 集成**: Dify Agent API + 智谱 GLM-4-Flash

### 前端
- **框架**: Vue.js 2.x + Element UI
- **HTTP 客户端**: Axios
- **Web 服务器**: Nginx 1.18.0

### 基础设施
- Docker Desktop（运行 Dify 等依赖服务）
- Redis 缓存
- RabbitMQ 消息队列

---

## 📁 项目结构

```
SparksHub/
├── Backend/                          # 后端 Spring Boot 项目
│   ├── src/main/java/com/hmdp/
│   │   ├── config/                   # 配置类（MVC、MyBatis、Redisson）
│   │   ├── controller/               # 控制器（Blog、Shop、User、Voucher）
│   │   ├── dto/                      # 数据传输对象
│   │   ├── entity/                   # 实体类（含 AiSummaryFeedback）
│   │   ├── interceptor/              # 登录拦截器 & Token 刷新
│   │   ├── listener/                 # 秒杀消息监听器
│   │   ├── mapper/                   # MyBatis-Plus Mapper
│   │   ├── service/                  # 业务逻辑层
│   │   │   └── impl/
│   │   │       ├── BlogServiceImpl.java      # AI 总结核心逻辑
│   │   │       └── AiSummaryFeedbackServiceImpl.java  # 反馈数据收集
│   │   └── utils/                    # 工具类（Redis、分布式锁、ID生成）
│   ├── src/main/resources/
│   │   ├── db/hmdp.sql               # 数据库初始化脚本
│   │   ├── mapper/                   # MyBatis XML 映射文件
│   │   ├── application.yaml          # 应用配置（含 Dify/GLM API Key）
│   │   ├── seckill.lua               # 秒杀 Lua 脚本
│   │   └── unLock.lua                # 分布式锁释放脚本
│   └── pom.xml                       # Maven 依赖
│
├── nginx-1.18.0/                     # 前端 + Nginx 服务
│   ├── conf/nginx.conf               # Nginx 配置（反向代理到后端）
│   └── html/hmdp/                    # 前端页面
│       ├── index.html                # 首页（商户列表）
│       ├── shop-detail.html          # 商户详情页
│       ├── blog-detail.html          # 笔记详情页（含 AI 总结按钮）
│       ├── blog-edit.html            # 发布笔记页
│       ├── login.html                # 登录页
│       ├── css/                      # 样式文件
│       ├── js/                       # JS 库（Vue、Element、Axios）
│       └── imgs/                     # 图片资源
│
├── .gitignore                        # Git 忽略规则
└── README.md                         # 项目说明文档
```

---

## 🧪 AI 总结功能测试

### 测试步骤

1. 确保已导入评论测试数据（通过 `hmdp.sql` 初始化）
2. 启动所有服务
3. 访问 `http://localhost:8081/blog-detail.html?id=4`
4. 向下滑动页面，点击右侧出现的 **"AI 总结"** 按钮
5. 等待弹窗生成总结内容

### 缓存验证

```bash
# 连接 Redis 查看缓存
redis-cli
> GET "blog:ai:summary:v2:4"
```

---

## 🔧 常见问题

### 1. Dify 返回 404

检查 `application.yaml` 中 Dify API URL 端口号是否正确（默认 `5173`）。

### 2. AI 总结内容与评论无关

- 确认数据库中有评论数据（`tb_blog_comments` 表）
- 检查 Dify 工作流输入变量名是否与后端传参一致
- 确认 Dify Output 节点输出变量名为 `summary_html`

### 3. Redis 连接失败

检查 `application.yaml` 中 Redis 配置（host、port、password），确保 Redis 服务已启动。


---

## 📊 反馈数据收集

AI 总结功能支持用户点赞/踩反馈，用于后续模型微调（SFT）：

- **前端**：弹窗底部提供 👍 / 👎 按钮
- **后端**：`AiSummaryFeedback` 表记录每次反馈
- **导出**：`GET /blog/ai-summary/feedback/export` 导出 JSON 格式数据

---

## 📄 许可证

本项目仅用于学习演示目的。
