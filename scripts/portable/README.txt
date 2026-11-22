TextDiffJava 便携包（v0.9.16+ 布局）
====================================

目录用途：
  bin/  lib/  conf/  legal/    核心程序与运行时（版本升级时整体替换，勿手工改动）
  data/                        用户数据（版本升级原样保留/平移，详见 data/README.txt）
  upgrade/                     平移升级脚本（upgrade.bat / upgrade.sh）

启动：
  Windows  bin\TextDiffJava.bat
  Linux    bin/TextDiffJava

升级：
  解压新包到独立目录后，在新包内执行 upgrade/upgrade.bat <旧安装目录>
  （Linux: upgrade/upgrade.sh <旧安装目录>）；旧安装原样保留作回退点，
  数据平移进本包 data/ 后自动冒烟启动验证。
