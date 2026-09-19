package io.github.aiarchguard.platform.finding.persistence;

import io.github.aiarchguard.platform.finding.internal.FindingIngestionStore;
import io.github.aiarchguard.platform.finding.internal.NormalizedEvidence;
import io.github.aiarchguard.platform.finding.internal.NormalizedFinding;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
final class JdbcFindingIngestionStore implements FindingIngestionStore {
    private final JdbcClient jdbc;
    JdbcFindingIngestionStore(JdbcClient jdbc){this.jdbc=jdbc;}
    @Override
    @Transactional
    public void store(UUID jobId,List<NormalizedEvidence> evidences,List<NormalizedFinding> findings){
        if(jdbc.sql("SELECT COUNT(*) FROM finding.findings WHERE job_id=:job").param("job",jobId).query(Long.class).single()>0){
            throw new IllegalStateException("Findings were already normalized for this job");
        }
        for(NormalizedEvidence e:evidences){
            jdbc.sql("""
                INSERT INTO finding.evidences
                  (id,project_id,job_id,scanner_evidence_id,kind,summary,path,start_line,start_column,end_line,end_column)
                VALUES (:id,:project,:job,:scanner,:kind,:summary,:path,:sl,:sc,:el,:ec)
                """).param("id",e.id()).param("project",e.projectId()).param("job",e.jobId()).param("scanner",e.scannerId())
                .param("kind",e.kind()).param("summary",e.summary()).param("path",e.location().path())
                .param("sl",e.location().startLine()).param("sc",e.location().startColumn())
                .param("el",e.location().endLine()).param("ec",e.location().endColumn()).update();
        }
        for(NormalizedFinding f:findings){
            jdbc.sql("""
                INSERT INTO finding.findings
                  (id,project_id,job_id,scanner_finding_id,fingerprint,rule_id,rule_version,severity,subject_id,message,
                   path,start_line,start_column,end_line,end_column,disposition,version)
                VALUES (:id,:project,:job,:scanner,:fingerprint,:rule,:ruleVersion,:severity,:subject,:message,
                   :path,:sl,:sc,:el,:ec,'OPEN',0)
                """).param("id",f.id()).param("project",f.projectId()).param("job",f.jobId()).param("scanner",f.scannerId())
                .param("fingerprint",f.fingerprint()).param("rule",f.ruleId()).param("ruleVersion",f.ruleVersion())
                .param("severity",f.severity()).param("subject",f.subjectId()).param("message",f.message())
                .param("path",f.location()==null?null:f.location().path()).param("sl",f.location()==null?null:f.location().startLine())
                .param("sc",f.location()==null?null:f.location().startColumn()).param("el",f.location()==null?null:f.location().endLine())
                .param("ec",f.location()==null?null:f.location().endColumn()).update();
            for(UUID evidenceId:f.evidenceIds()) jdbc.sql("INSERT INTO finding.finding_evidences (finding_id,evidence_id) VALUES (:finding,:evidence)")
                .param("finding",f.id()).param("evidence",evidenceId).update();
        }
    }
}
