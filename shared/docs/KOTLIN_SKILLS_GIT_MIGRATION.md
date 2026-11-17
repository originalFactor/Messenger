# Kotlin Skills：通用 Git HTTPS 迁移评估

本文件记录调研结论，不代表生产能力。当前 Kotlin `RemoteSkillImport` 只支持公开 GitHub HTTPS API；通用 Git HTTPS、私有仓库凭证、界面与生产接线均未实现。Rust Core 的通用 Git 导入在切换前仍须保留，不能将 Kotlin 的 GitHub 实现当成完整替代。

GitHub tree/blob 链接按路径从长到短查询精确的分支和标签引用，支持包含斜杠的名称；同名分支优先，附注标签按不可变对象 ID 解析到提交。显式指定 ref 和路径时直接使用用户选择。仅 404 表示候选引用不存在，其他失败会直接显示；每次查询都计入现有请求次数和时间预算，成功响应另受流量上限约束。精确查询避免引用列表分页或截断造成错误匹配，但较深路径会产生更多请求，可能触发 GitHub 未认证访问的速率限制；遇限时导入失败，不会选择较短引用。查询之间引用可能变化，因此不是原子引用快照；发现后返回固定提交，预览仍必须使用该提交。

## JGit 的实际成本和限制

JGit 是 [纯 Java 实现](https://github.com/eclipse-jgit/jgit)，但其默认 Git 传输不满足本项目对不可信仓库的限制。本机 Gradle 缓存中的 `org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r` 核心 JAR 为 3,448,442 字节；其 POM 还依赖 JavaEWAH 1.2.3（170,567 字节）、SLF4J API 2.0.18（69,982 字节）和 commons-codec 1.22.1（422,738 字节），合计约 3.92 MiB 的 JAR 文件。它们可能与现有依赖重叠；这是依赖文件大小，不是 APK、R8 或 Desktop 安装包的实际增量，也不能推断编译时间变化。7.8 的本地 manifest 要求 Java 17；上游 [已决定 JGit 8 改用 Java 25](https://github.com/eclipse-jgit/jgit/issues/292)。Android ART、D8/R8 及目标 API 的兼容性仍需实机和发布构建验证，不能由纯 Java 推断。

本机 7.8 源码 `BasePackFetchConnection` 创建 `PackParser` 后没有设置对象大小上限。即使直接使用解析器并设置 [`setMaxObjectSizeLimit`](https://download.eclipse.org/jgit/site/6.4.0.202211300538-r/org.eclipse.jgit/apidocs/org.eclipse.jgit/org/eclipse/jgit/transport/PackParser.html)，限制也不覆盖 pack 头声明的对象数：其实现先用声明值创建对象数组。本机用独立 `-Xmx64m` JVM、12 字节的 `PACK` v2 头和 `0x7fffffff` 对象数进行验证；即使设置 1 MiB 的单对象限制，解析仍触发 `OutOfMemoryError`。这个测试只证明该输入能突破单对象限制，不证明其他 JGit 版本或所有 pack 路径的行为。

JGit 默认 HTTP transport 使用系统代理和 Git 配置；系统配置发现路径还可能调用系统 Git。它有[单 transport 的 HTTP factory 注入接口](https://download.eclipse.org/jgit/site/6.4.0.202211300538-r/org.eclipse.jgit/apidocs/org.eclipse.jgit/org/eclipse/jgit/transport/TransportHttp.html)，但更换 factory 并不会自动限制 pack 解压、对象数或取消延迟。不要直接使用默认 `clone` / `fetch`、checkout、系统凭证、用户 Git 配置、hooks 或 Git 子进程处理不可信仓库。

## 可行方向和必须验证的约束

可以先研究由受控 OkHttp 实现只读 Git smart HTTPS：限制 refs、pkt-line、side-band 和压缩 pack 的字节数与请求数；将 ref 解析为提交 ID 后固定该 ID；禁止重定向、环境代理和自动携带凭证。协议和 pack 格式须按 [gitprotocol-pack](https://git-scm.com/docs/gitprotocol-pack) 和 [gitformat-pack](https://git-scm.com/docs/gitformat-pack) 实现，不能假设 shallow fetch 会自动满足大小限制。对每次连接解析全部 DNS 答案，拒绝本地/保留地址并固定经过验证的地址，同时保留原主机名用于 TLS 校验。限制并发、总时间和响应体；DNS 在部分平台不可中断，单纯协程超时不能证明解析线程已经退出。

解析 pack 之前至少检查完整头、版本、压缩字节数及对象数，之后还需要单对象和**累计**解压量、delta 深度及循环、索引/缓存、路径、对象和包大小的独立预算。不得启用 thin-pack 外部对象回填，须校验 pack checksum、提交/树/blob 对象 ID、目录深度和 symlink/submodule 类型，再按现有 SKILL.md、文件数和包大小规则校验。对未知对象格式、超限、校验失败或取消均拒绝导入并保留已有数据。JGit 的单对象上限和协作式 progress cancellation 都不是总内存或硬超时证明；恶意 delta 链和压缩数据仍需专门处理。实现前应验证解析器能否可靠施加这些预算，否则需要额外隔离或继续不支持，而不是放宽约束。

若将来支持私有仓库，凭证只能由用户明确提供并限定到原始 HTTPS origin；只允许 Git upload-pack 读取请求，不接受 URL 中的凭证、跨 origin 重定向、环境代理、全局 credential helper、cookie 或透传到模型/日志。客户端只能约束自身请求，不能替服务端证明 token 是只读的；需要由服务端颁发最小权限的只读凭证。当前实现不接受此类凭证。

落地前至少需要：对畸形包头/对象数、压缩炸弹、深或循环 delta、截断 pack、伪造 hash、symlink/submodule、DNS 重绑定、TLS 主机名、取消及并发限制做单元测试和 fuzz；验证私有目录与异常清理；在 Android 真机/模拟器及 Windows、Linux Desktop 验证相同行为，并实测 release APK/安装包增量与构建时间。不要把一次 JVM 测试或数个阈值当作完整安全证明。

工作量仅供规划：受限 HTTPS/协议层约数个工程日；可审计的 pack 预算、对抗测试与跨平台验证通常需要额外数周，具体取决于可复用解析器是否能证明累计内存和取消约束。达不到验证条件时，继续只支持当前 GitHub API 是明确的安全边界。
