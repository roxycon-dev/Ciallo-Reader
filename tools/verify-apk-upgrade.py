"""Validate a release APK and its compatibility with a previously published APK."""
import argparse
import pathlib
import re
import subprocess


def apk_identity(badging, certificates):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.M)
    fingerprints = set(re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", certificates))
    if not package or not fingerprints:
        raise ValueError("无法读取 APK 包名、版本编号或签名证书")
    return package[1], int(package[2]), {value.lower() for value in fingerprints}


def validate_upgrade(new, previous, expected, debuggable=False):
    if new[0] != "com.aistudio.novelreader.kxmpzq":
        raise ValueError("正式 APK 的应用标识不正确")
    if new[2] != {expected}:
        raise ValueError("正式 APK 签名与固定证书不一致")
    if debuggable:
        raise ValueError("正式 APK 不应开启调试模式")
    if previous:
        if new[0] != previous[0] or new[2] != previous[2]:
            raise ValueError("新旧 APK 的应用标识或签名不同，无法保留数据覆盖安装")
        if new[1] <= previous[1]:
            raise ValueError("新 APK 的 versionCode 必须大于已发布版本")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--new-apk", required=True, type=pathlib.Path)
    parser.add_argument("--previous-apk", type=pathlib.Path)
    parser.add_argument("--build-tools", required=True, type=pathlib.Path)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    expected = (root / "signing/release-cert.sha256").read_text().strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        parser.error("固定证书指纹无效")

    def inspect(apk):
        signing = args.build_tools / ("apksigner.bat" if (args.build_tools / "apksigner.bat").exists() else "apksigner")
        aapt = args.build_tools / ("aapt.exe" if (args.build_tools / "aapt.exe").exists() else "aapt")
        cert = subprocess.check_output([str(signing), "verify", "--print-certs", str(apk)], text=True, encoding="utf-8")
        badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True, encoding="utf-8")
        return apk_identity(badging, cert), "application-debuggable" in badging

    try:
        new, debug = inspect(args.new_apk)
        previous = inspect(args.previous_apk)[0] if args.previous_apk else None
        validate_upgrade(new, previous, expected, debug)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + "\n")
    print("APK 签名及正式应用标识通过校验" + ("；新旧版本满足覆盖升级条件" if previous else "；未提供旧 APK，尚未验证覆盖升级"))


if __name__ == "__main__":
    main()
