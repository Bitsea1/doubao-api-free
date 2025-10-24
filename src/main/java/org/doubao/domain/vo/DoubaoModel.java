package org.doubao.domain.vo;

import lombok.Data;
import java.time.Instant;

@Data
public class DoubaoModel {
    private String id;
    private String object = "model";
    private Long created;
    private String ownedBy = "doubao";

    public DoubaoModel(String id) {
        this.id = id;
        this.created = Instant.now().getEpochSecond();
    }
}
