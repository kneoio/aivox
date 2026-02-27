package com.semantyca.aivox.model.soundfragment;

import com.semantyca.aivox.model.cnst.ApprovalStatus;

import java.time.LocalDateTime;

public record ApprovalStatusChange(LocalDateTime timestamp, ApprovalStatus oldStatus,
                                   ApprovalStatus newStatus) {
}
