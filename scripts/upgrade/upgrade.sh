#!/usr/bin/env bash
# ============================================================
#  TextDiffJava 平移升级（Linux）
#  新版本解压/解包到独立目录后，把旧安装的用户数据平移过来，
#  旧目录原样保留（天然回退点），最后冒烟启动验证。
#
#  用法（任选其一）：
#    1) 在新包内运行:   upgrade/upgrade.sh <旧安装目录>
#    2) 在旧安装根运行: upgrade.sh <新便携包.zip>
#    可选尾参: --no-start  跳过冒烟启动
#
#  平移项: config.ini store results results_split configs
#  （数据若曾按 bin 为工作目录运行而落在旧 bin/ 下，自动识别）
# ============================================================
set -u

script_dir="$(cd "$(dirname "$0")" && pwd)"
no_start=""

usage() {
  echo "用法: upgrade.sh <新便携包.zip 或 旧安装目录> [旧安装目录] [--no-start]"
  echo "  方式一（推荐，脚本随新包携带）: 解压新包后，在其目录内执行 upgrade/upgrade.sh <旧安装目录>"
  echo "  方式二（旧目录内）: upgrade.sh <新便携包.zip>"
}

fail() { echo "升级未完成（数据未受影响，旧目录原样）。" >&2; exit 1; }

args=()
for a in "$@"; do
  if [ "$a" = "--no-start" ]; then no_start=1; else args+=("$a"); fi
done
[ ${#args[@]} -ge 1 ] || { usage; fail; }

arg1="${args[0]}"
old="${args[1]:-$PWD}"

# ---------- 服务必须已停止 ----------
probe() {  # 0=在运行
  if command -v curl >/dev/null 2>&1; then
    curl -s -m 3 http://127.0.0.1:8080/api/health 2>/dev/null | grep -q . && return 0
  elif command -v wget >/dev/null 2>&1; then
    wget -q -T 3 -O - http://127.0.0.1:8080/api/health 2>/dev/null | grep -q . && return 0
  fi
  return 1
}
echo "[2/5] 检查 8080 服务是否已停止 ..."
if probe; then
  echo "[中止] 服务仍在运行，请先停止旧服务再升级。"
  fail
fi

# ---------- 定位新安装 ----------
new=""
case "$arg1" in
  *.zip|*.ZIP)
    zip_file="$arg1"
    [ -f "$zip_file" ] || { echo "[错误] zip 不存在: $zip_file"; fail; }
    work="${old}.new-tmp"
    echo "[1/5] 解压新包 -> $work"
    rm -rf "$work"; mkdir -p "$work"
    if command -v unzip >/dev/null 2>&1; then
      unzip -q "$zip_file" -d "$work" || { echo "[错误] 解压失败"; fail; }
    elif command -v python3 >/dev/null 2>&1; then
      python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$zip_file" "$work" \
        || { echo "[错误] 解压失败"; fail; }
    else
      echo "[错误] 系统缺少 unzip / python3，无法解压"; fail
    fi
    entries=("$work"/*)
    if [ ${#entries[@]} -eq 1 ] && [ -d "${entries[0]}" ]; then new="${entries[0]}"; else new="$work"; fi
    ;;
  *)
    if [ -d "$arg1/bin" ]; then
      if [ -d "$script_dir/../bin" ]; then
        new="$script_dir/.."      # 脚本随新包携带：参数视为旧目录
        old="$arg1"
      elif [ -n "${args[1]:-}" ]; then
        new="$arg1"; old="${args[1]}"   # 显式给出两个目录：arg1=新 arg2=旧
      else
        echo "[错误] 脚本不在安装包内运行且只给了一个目录，无法区分新/旧。"
        echo "       请改用随新包携带的 upgrade/upgrade.sh <旧目录>，或传 .zip，或给出两个目录。"
        fail
      fi
    else
      echo "[错误] 参数既不是 .zip 也不像安装目录（缺 bin）: $arg1"; fail
    fi
    ;;
esac

[ -d "$old" ] || { echo "[错误] 旧目录不存在: $old"; fail; }
[ -d "$new/bin" ] || { echo "[错误] 新目录不像安装目录（缺 bin）: $new"; fail; }

# ---------- 数据位置（根目录优先，兼容 bin 工作目录） ----------
src="$old"
[ -f "$old/config.ini" ] || [ -f "$old/bin/config.ini" ] && src="$old/bin"
old_abs="$(cd "$old" && pwd)"
new_abs="$(cd "$new" && pwd)"
src_abs="$(cd "$src" && pwd)"
echo "[3/5] 旧安装: $old_abs"
echo "      数据位置: $src_abs"
echo "      新安装: $new_abs"

has_data=0
[ -f "$src_abs/config.ini" ] && has_data=1
for d in store results results_split configs; do
  [ -d "$src_abs/$d" ] && has_data=1
done
[ "$has_data" = "0" ] && echo "[警告] 未发现任何用户数据（config.ini/store/results/configs），仅做目录切换。"

# ---------- 平移（复制制：旧目录原样保留） ----------
echo "[4/5] 平移数据（复制，旧目录不动作回退点）..."
if [ -f "$src_abs/config.ini" ]; then
  [ -f "$new_abs/config.ini" ] && echo "      注意: 新目录已有 config.ini，以旧数据覆盖"
  cp -p "$src_abs/config.ini" "$new_abs/config.ini" && echo "      已迁移: config.ini"
fi
for d in store results results_split configs; do
  if [ -d "$src_abs/$d" ]; then
    mkdir -p "$new_abs/$d"
    cp -a "$src_abs/$d/." "$new_abs/$d/" && echo "      已迁移: $d/"
  fi
done

# ---------- 冒烟启动 ----------
if [ "$no_start" = "1" ]; then
  echo "[5/5] 跳过冒烟启动（按要求）。"
else
  echo "[5/5] 冒烟启动并等待就绪（至多 90 秒）..."
  ( cd "$new_abs" && nohup bin/TextDiffJava > upgrade-start.log 2>&1 & )
  ready=0
  for i in $(seq 1 45); do
    sleep 2
    if probe; then ready=1; break; fi
  done
  if [ "$ready" = "1" ]; then
    echo "      服务已就绪:"
    curl -s -m 3 http://127.0.0.1:8080/api/health 2>/dev/null || wget -q -T 3 -O - http://127.0.0.1:8080/api/health 2>/dev/null
    echo
  else
    echo "[警告] 90 秒内未就绪。查看 $new_abs/upgrade-start.log 排查；"
    echo "       新目录可整目录删除后回到旧目录启动即完成回退。"
  fi
fi

echo "============================================================"
echo " 升级完成。旧目录原样保留: $old_abs"
echo "   回退 = 停止本服务，进入旧目录运行 bin/TextDiffJava"
echo "   确认运行无误数日后，旧目录与临时解压目录可自行删除。"
echo "============================================================"
