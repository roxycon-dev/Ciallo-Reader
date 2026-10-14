# 书源返回格式修复 · 2026-10-13

代码修复基于 main `2c9886927ccdd68f18ace8d91306271df20617cd`，发布时保留后续官网更新。应用版本 1.3.7 / 218，发布记录见 [v1.3.7](release-1.3.7-2026-10-13.md)。

## 两个反馈的原因

- 绅士漫画上游 1.0.6 新增连载分页后，原有整段字符串补丁无法匹配。实际 loadInfo 仍直接读取标题、封面、label、简介和上传者，任何节点缺失都可能抛出 null.text。现在用独立运行时适配器接管详情，兼容两套页面布局，保留连载分页、去重和顺序。必需作品标识缺失时明确报错，可选字段缺失允许为空；单图集的阅读链接不会误判为连载。所有成功、失败路径释放 DOM。
- 禁漫天堂旧修复只在 HTTP 404 切换域名。HTTP 200 的 HTML 错误页直接进入 JSON.parse，产生 unexpected token '<'。现在先检查 JSON 信封、加密数据类型及解密后的 JSON，再接受线路；失败尝试其余已提供线路。临时成功线路与用户选择分开，用户改选线路即时生效；401 / 登录拒绝停止，POST 不跨域重放。GET 与取消受原有执行预算约束。

## 审计与共用处理范围

- 获取 Venera 上游 index 的全部 33 份脚本快照，检查搜索、详情、目录、图片入口及 JSON / HTML 使用；其中也包含本项目原本排除的来源。排除列表不因本轮而改变。
- 本地内置 bilimanga、vomic、pufei 与其原有补丁；检查原生 MangaDex、AutoNovel、Wenku8Library、Ixdzs、Z-Library HTML / eapi、自定义 JSON/Legado 解析入口。
- JS 共用 JSON 解析支持 BOM，保留有效对象、数组、标量、null 与 reviver 语义；错误诊断不包含返回正文。HTML 验证 / 拦截页与网络故障提供可操作提示。
- fetch.text / fetch.json 也按响应字符集解码，arrayBuffer 保留原始二进制语义；章节 Map / 对象 / 分组数组保留真实字段类型，不把 null 或章节对象强转成标题字符串。
- 全部 JS 搜索 / 目录 / 图片结果按契约检查：必需列表不得为 null 或对象；作品与章节编号 / 标题不得由 null 或对象转换成字符串；缺失图片、异常章节不得被静默丢弃后缓存。合法空搜索列表和省略目录的单图集继续支持。
- 共用解码尊重 HTTP charset、HTML GB 声明与 UTF BOM；无编码声明时用严格 UTF-8 检查后才回退 GB18030，合法的 Unicode 替换字符不会导致误切编码。原生 JSON 必须为对象或数组，拒绝 HTML、空响应、截断及尾部垃圾。
- OkHttp / Chromium 自行协商支持的压缩，避免上游的 br / zstd 请求头禁用透明解压；明确的 identity 请求保留，二进制接口不应用文本结构检查。
- 自定义 JSON 源的搜索路径必须匹配对象 / 数组；空数组是无结果，缺失路径、非对象条目或全部缺少书名 / 编号则报告规则不匹配。

## 可复现验证

```text
npm install --prefix .tools/dom linkedom --no-audit --no-fund
node tools/verify-source-response.cjs
node tools/verify-comic-source-host.cjs
gradlew.bat :app:testDebugUnitTest --tests "com.example.source.*" --tests "com.example.data.AppUpdateDownloadTest"
gradlew.bat :app:assembleDebug
```

- 新宿主回归：JSON / reviver、桌面 / 移动 HTML、单图集、多页连载、错误页、缺页、域名切换、非法数据与登录拒绝。
- 原有宿主回归：源脚本和桥语法、nhentai 请求头、comick 目录分页、搜索票据、Cookie、图片批次。
- 新 JVM 回归：文本编码 / BOM、JSON 响应结构、有效空列表、异常图片 / 目录、JSONPath 严格搜索、HTTP gzip 协商。

本轮验证针对代码与合成响应；未拥有各源账号，也没有用户这两部作品的完整请求日志。语法审计不等于所有站点在所有地区实时可用，不承诺修复站点下架、地区限制或必须人工完成的验证。

## 本轮执行结果

- 15 项新增 Node 宿主检查通过；实际载入上游 wnacg.js 1.0.6，在移动页样例复现旧 null.text，并验证新适配器修复。
- 7 项原有源宿主回归通过；原有漫画元数据、构造器和目录归一化工具检查通过，全部原有断言保留。
- 本次改动涉及的 13 个 JVM / Robolectric 套件共 79 项通过，失败和错误均为 0。覆盖响应编码、HTTP gzip、JSON / HTML、搜索与目录结构、元数据、导入与规则、MangaDex 章节回退、Z-Library HTML 解析及更新下载。Legado 规则测试补齐 Android 测试运行环境，生产正则预算不变。
- 扩展书源检查共 176 项：172 项通过，4 项失败。两个 KeywordRepositoryTest 失败于持久缓存内容断言；关键词仓库、缓存与在线查询生产文件均与上游提交完全一致，本轮未修改。ZLibraryFlowIntegrationTest 的在线搜索超时，ZLibrarySourceTest 的伪造 Cookie 登录未通过。另有直接访问站点的旧测试因长时间网络等待中断；不能宣称全部书源测试已通过。
- 标准 Debug APK 构建通过。另行构建并核验可并存的测试包：包名 `com.aistudio.novelreader.kxmpzq.sourceformat`，名称「Ciallo Reader · 书源测试」，版本 1.3.6 / 217，Android 7.0+、arm64-v8a；签名验证和 ZIP CRC 检查通过，三份响应修复适配脚本已打包。它使用独立数据目录，需在测试应用中重新启用或登录书源，不会沿用正式版的设置。
- 测试 APK：`artifacts/Ciallo-Reader-1.3.6-source-format-test.apk`。SHA-256：`22c6207978fbb9f5bae01aff7c457ed4379baf64f9afd7fadd48d6b9045666f7`。

Windows 当前执行使用临时 `.source-verification/test-init.gradle`，从本地加载经官方 SHA-256 校验的测试运行库；Android 13 / 15 测试镜像从可达 Maven 镜像获取后核对官方哈希。依赖与测试镜像均不进入 APK。
