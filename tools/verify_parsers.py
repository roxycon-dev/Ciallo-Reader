"""离线编译并运行 AliceSw 解析层的 JUnit 用例。

本机没有 Android SDK，所以只编译不依赖 Android 的部分：
真实的 AliceSwParser.kt / AliceSwDiscovery.kt / 两个测试类照原样编译，
数据类与 SOURCE_ID 用等价替身补齐（它们只是字段容器，不含解析逻辑）。
"""
import glob
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KV = os.path.join(os.path.dirname(ROOT), "kverify")  # 编译器 jar 放在工作区根目录
JAVA = r"C:\Program Files\JetBrains\PyCharm 2026.2.1\jbr\bin\java.exe"
HARNESS = os.path.join(KV, "harness")
SRC = os.path.join(HARNESS, "src")
OUT = os.path.join(HARNESS, "out")

STUBS = {
    "com/example/source/ComicChapter.kt": """package com.example.source

data class ComicChapter(
    val id: String,
    val title: String,
    val volume: String? = null,
    val order: Float = 0f,
    val external: Boolean = false,
    val externalUrl: String? = null
)
""",
    "com/example/source/NovelInfo.kt": """package com.example.source

data class NovelInfo(
    val synopsis: String? = null,
    val originalTitle: String? = null,
    val status: String? = null,
    val category: String? = null,
    val latestChapter: String? = null,
    val updatedAt: String? = null,
    val chapterCount: Int? = null,
    val volumeCount: Int? = null,
    val wordCount: String? = null,
    val publisher: String? = null,
    val tags: List<String> = emptyList(),
    val translation: String? = null,
    val notice: String? = null,
)
""",
    "com/example/source/SearchBook.kt": """package com.example.source

data class SearchBook(
    val id: String,
    val sourceId: String,
    val title: String,
    val author: String,
    val cover: String? = null,
    val description: String? = null,
    val format: String = "epub",
    val language: String? = null,
    val comicId: String? = null,
    val size: Long? = null,
    val downloadUrl: String? = null,
    val novelInfo: NovelInfo? = null
)
""",
    "com/example/source/SourceException.kt": """package com.example.source

sealed class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object LoginRequired : SourceException("需要登录后继续操作")
    data class NetworkError(val errorMsg: String, val throwable: Throwable? = null) : SourceException(errorMsg, throwable)
    data class ParseError(val errorMsg: String) : SourceException(errorMsg)
    object BookNotFound : SourceException("未能找到对应图书")
    data class Unknown(val errorMsg: String, val throwable: Throwable? = null) : SourceException(errorMsg, throwable)
}
""",
    "com/example/source/impl/AliceSwSourceId.kt": """package com.example.source.impl

/** 编译替身：真实 AliceSwSource 依赖 Android Context，这里只补测试用到的那一个常量。 */
object AliceSwSource {
    const val SOURCE_ID = "alicesw"
    const val EPUB_MARKER = "ciallo-alicesw-epub-v1"
}
""",
}


def run(cmd, **kw):
    print("+", " ".join(cmd) if isinstance(cmd, list) else cmd)
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", **kw)
    if result.stdout.strip():
        print(result.stdout.strip())
    if result.returncode != 0:
        print("STDERR:", result.stderr.strip()[:6000])
        sys.exit(result.returncode)
    return result


def main():
    shutil.rmtree(HARNESS, ignore_errors=True)
    os.makedirs(SRC, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)

    for rel, text in STUBS.items():
        path = os.path.join(SRC, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)

    copies = [
        "app/src/main/java/com/example/source/impl/AliceSwParser.kt",
        "app/src/main/java/com/example/source/impl/AliceSwDiscovery.kt",
        "app/src/test/java/com/example/source/impl/AliceSwParserTest.kt",
        "app/src/test/java/com/example/source/impl/AliceSwDiscoveryTest.kt",
    ]
    for rel in copies:
        dst = os.path.join(SRC, os.path.basename(rel))
        shutil.copyfile(os.path.join(ROOT, rel), dst)
        print("copied", rel)

    compiler_jars = ";".join(glob.glob(os.path.join(KV, "compiler", "*.jar")))
    lib_jars = ";".join(glob.glob(os.path.join(KV, "lib", "*.jar")))
    compiler = os.path.join(KV, "compiler", "kotlin-compiler-220.jar")  # 2.2.20：本机 JDK 25 下 2.0.21 解析版本号会崩
    cp = ";".join(filter(None, [compiler, compiler_jars]))
    sources = [p for p in glob.glob(os.path.join(SRC, "**", "*.kt"), recursive=True)]
    run([JAVA, "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
         "-no-stdlib", "-jvm-target", "17", "-classpath", lib_jars, "-d", OUT] + sources)

    test_cp = ";".join([OUT, lib_jars, os.path.join(ROOT, "app", "src", "test", "resources")])
    run([JAVA, "-cp", test_cp, "org.junit.runner.JUnitCore",
         "com.example.source.impl.AliceSwParserTest",
         "com.example.source.impl.AliceSwDiscoveryTest"])


if __name__ == "__main__":
    main()
