本目录存放 TextDiffJava 的全部用户数据（版本升级时只平移本目录，程序件整体替换）：
  config.ini        服务与引擎配置（含 [jvm] jvm_opts）
  configs/          对比配置文件（current.conf 等）
  store/            作业/批次/映射等事实数据（JSONL + H2 镜像）
  results/          对比结果与导出产物
  results_split/    拆分模块产物
  uploads/          上传文件暂存
  customcmp/        自定义格式对比数据
  reportcmp/        报表对比数据
