package io.github.aiarchguard.platform.scanjob.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 通过共享邮箱中的 jobId-attemptToken.cancel 文件向 Runner 发出尽力取消信号。
 * 本适配器只创建信号文件，不等待确认、不直接终止进程，也不保证失败后的恢复。
 */
@Component
final class MailboxRunnerCancellationAdapter implements RunnerCancellationPort {
    private final Path cancellations;
    MailboxRunnerCancellationAdapter(@Value("${archguard.runner.mailbox:./runner-mailbox}") String mailbox) {
        this.cancellations=Path.of(mailbox).toAbsolutePath().normalize().resolve("cancellations");
    }
    /** 无 token 时无可定位的 attempt；文件创建失败被忽略，数据库中的取消意图仍由调用方保存。 */
    @Override public void request(UUID jobId,UUID attemptToken) {
        if(attemptToken==null)return;
        try { Files.createDirectories(cancellations); Files.createFile(cancellations.resolve(jobId+"-"+attemptToken+".cancel")); }
        catch(IOException ignored) { /* 仅尽力投递；当前认领逻辑不接管 CANCEL_REQUESTED，租约不保证取消失败后恢复。 */ }
    }
}
