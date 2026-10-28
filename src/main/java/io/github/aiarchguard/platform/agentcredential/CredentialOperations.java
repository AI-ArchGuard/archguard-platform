package io.github.aiarchguard.platform.agentcredential;

/** 只写个人凭据管理边界；返回状态元数据，不提供 Key 读取、有效性探测或模型调用。 */
public interface CredentialOperations {
    /** 核验配置 owner、本地部署开关、传输与 Origin；写操作要求显式匹配 Origin。 */
    void authorize(String origin, boolean secure, boolean mutation);
    /** 已授权的畸形写入只记安全失败审计，不保存请求原文。 */
    void rejectInvalidWrite();
    /** 认证当前密文后返回 configured、版本和时间。 */
    CredentialStatus status();
    /** 校验并原子替换固定槽位，产生新版本；保存不等于批准外发。 */
    CredentialStatus write(String apiKey);
    /** 先认证既有密文再删除，不回退旧文件或环境中的 Key。 */
    CredentialStatus delete();
}
