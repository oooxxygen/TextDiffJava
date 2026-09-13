package com.textdiff.store;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 外部命令信箱记录：外部（IDE/SQL）运行期间向 H2 commands 表插入命令，JobManager 轮询消费。
 * 仅存 H2、不进镜像/文件层。状态机：pending → running → done / error；
 * 进程中断残留的 running 在重启时重置回 pending 重放。
 */
public final class CommandRecord {
    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    public String id;
    /** retry / cancel / create_batch。 */
    public String type;
    /** JSON 参数，按 type 取 jobId 或 dirA/dirB/configLines。 */
    public String payload;
    public String status = PENDING;
    /** 执行结果描述或错误信息。 */
    public String result;
    public long createdAt;
    public Long completedAt;

    public CommandRecord() {}

    public CommandRecord(String id, String type, String payload) {
        this.id = id;
        this.type = type;
        this.payload = payload;
        this.createdAt = System.currentTimeMillis() / 1000;
    }

    /** 命令 payload 反序列化载体：字段按命令类型按需填充，未知字段忽略。 */
    public static final class Payload {
        @JsonProperty("jobId") public String jobId;
        @JsonProperty("dirA") public String dirA;
        @JsonProperty("dirB") public String dirB;
        @JsonProperty("configLines") public java.util.List<String> configLines;
    }
}
