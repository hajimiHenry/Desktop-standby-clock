#!/usr/bin/env bash
#
# 打正式发布版 APK：跑测试和 lint、编译、签名、校验签名，最后把产物连同
# SHA-256 校验和一起放到 dist/ 目录。
#
# 前置条件：JDK 17、Android SDK、以及一个发布用的签名密钥库。
# 详细的发布流程见 docs/RELEASING.md。

# -e 出错即停；-u 用到未定义变量即报错；-o pipefail 让管道中任一环节失败都算失败。
# 打包脚本尤其需要这三个：默默跳过一步却仍然产出一个 APK，比直接失败危险得多。
set -euo pipefail

# 定位到仓库根目录，这样脚本在哪里被调用都能正常工作。
# CDPATH= 是防止用户配了 CDPATH 环境变量导致 cd 跑到别的地方去。
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
cd "$repository_root"

# 版本号从 build.gradle 里抠出来，避免脚本和构建配置各写一份导致对不上。
release_version=$(sed -n 's/^[[:space:]]*versionName[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle)
if [[ -z "$release_version" ]]; then
    echo "Unable to read versionName from app/build.gradle." >&2
    exit 1
fi

# ${HOME:?...} 的意思是 HOME 没设置就直接带着这句提示退出。
user_home_dir=${HOME:?HOME is required}
# ${VAR:-默认值}：允许外部覆盖，没给就用默认。密钥库放在仓库外，绝不能提交进 Git。
export STANDBY_CLOCK_KEYSTORE=${STANDBY_CLOCK_KEYSTORE:-"$user_home_dir/.android/standby-clock-release.jks"}
export STANDBY_CLOCK_KEY_ALIAS=${STANDBY_CLOCK_KEY_ALIAS:-standby-clock}

# 密码优先从 macOS 钥匙串里取，这样不用把它写进环境变量或者任何文件。
# 非 macOS 环境（没有 security 命令）则要求调用者自己通过环境变量提供。
if [[ -z "${STANDBY_CLOCK_STORE_PASSWORD:-}" ]] && command -v security >/dev/null 2>&1; then
    STANDBY_CLOCK_STORE_PASSWORD=$(security find-generic-password \
        -a standby-clock \
        -s com.henry.standbyclock.release-signing \
        -w)
    export STANDBY_CLOCK_STORE_PASSWORD
fi
# 密钥密码默认与密钥库密码相同，这是 keytool 生成密钥时最常见的情况。
export STANDBY_CLOCK_KEY_PASSWORD=${STANDBY_CLOCK_KEY_PASSWORD:-${STANDBY_CLOCK_STORE_PASSWORD:-}}

if [[ ! -f "$STANDBY_CLOCK_KEYSTORE" ]]; then
    echo "Release keystore not found: $STANDBY_CLOCK_KEYSTORE" >&2
    exit 1
fi
if [[ -z "${STANDBY_CLOCK_STORE_PASSWORD:-}" || -z "$STANDBY_CLOCK_KEY_PASSWORD" ]]; then
    echo "Release signing passwords are not configured." >&2
    exit 1
fi

# JAVA_HOME 没设时，试一下 Homebrew 装 JDK 17 的默认路径。
# 这个项目必须用 JDK 17，高版本编译 Android Gradle Plugin 会出问题。
if [[ -z "${JAVA_HOME:-}" ]]; then
    homebrew_jdk17=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    if [[ -x "$homebrew_jdk17/bin/java" ]]; then
        export JAVA_HOME=$homebrew_jdk17
    fi
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "JDK 17 was not found. Set JAVA_HOME before running this script." >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

# 一条命令干完：清理、跑单测、跑 lint、编译签名版。
# 测试和 lint 放在打包之前，任何一步失败都会因为 set -e 而中止，
# 保证不会打出一个没过测试的包。--no-daemon 是为了发布构建的环境干净可复现。
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleRelease

# 确认产物真的生成了。签名配置缺失时 Gradle 可能只警告不报错，
# 这一步就是防止那种情况被忽略过去。
signed_apk=app/build/outputs/apk/release/app-release.apk
if [[ ! -f "$signed_apk" ]]; then
    echo "Signed release APK was not produced: $signed_apk" >&2
    exit 1
fi

# 找 Android SDK：先看两个标准环境变量，都没有就从 local.properties 里读。
android_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$android_sdk" && -f local.properties ]]; then
    android_sdk=$(sed -n 's/^sdk.dir=//p' local.properties)
fi
if [[ -z "$android_sdk" || ! -d "$android_sdk/build-tools" ]]; then
    echo "Android SDK build-tools were not found." >&2
    exit 1
fi
# build-tools 下每个版本一个目录，这里取版本号最高的那个。
# sort -V 是按版本号排序，不然 "34.0.0" 会被字典序排在 "9.0.0" 前面。
apksigner_path=$(find "$android_sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner | sort -V | tail -n 1)
if [[ -z "$apksigner_path" ]]; then
    echo "apksigner was not found in the configured Android SDK." >&2
    exit 1
fi

# 验证签名并打印证书信息。发布前用它肉眼确认签的是对的那把钥匙——
# 签错了的话用户装不上升级包，而且事后无法补救。
"$apksigner_path" verify --verbose --print-certs "$signed_apk"

# 产物按版本号重命名后放进 dist/。用 install 而不是 cp 是为了顺便定好权限位。
mkdir -p dist
release_name="Desktop-Standby-Clock-v${release_version}.apk"
install -m 0644 "$signed_apk" "dist/$release_name"
# 放在子 shell 里 cd，这样校验和文件里只有文件名没有路径前缀，
# 同时也不影响外层脚本的当前目录。
(
    cd dist
    shasum -a 256 "$release_name" > "$release_name.sha256"
)

echo "Release artifacts:"
echo "  dist/$release_name"
echo "  dist/$release_name.sha256"
