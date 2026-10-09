可选驱动目录
================

默认发布包内置 SQLite 驱动（已打进 xtlog-server.jar），开箱即用，本目录留空即可。

只有当你要把数据源切到 MySQL / PostgreSQL 时，才需要手工放驱动 jar 到这里：

  MySQL       mysql-connector-j-8.x.x.jar
  PostgreSQL  postgresql-42.x.x.jar

启动脚本已把 drivers/* 加入 classpath，放进来重启服务即可生效。
放好驱动后，再在系统「设置 → 初始化配置」里把数据源切换到对应的 JDBC URL：

  MySQL       jdbc:mysql://主机:3306/xtlog?useUnicode=true&characterEncoding=utf8
  PostgreSQL  jdbc:postgresql://主机:5432/xtlog

切换结果会写入 data/datasource.properties，之后每次启动自动使用该数据源。
删除 data/datasource.properties 并重启，即可回到内置 SQLite。
