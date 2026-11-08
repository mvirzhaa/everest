-- v0.3: eskalasi panic yang tidak ditanggapi + catatan persetujuan privasi (UU PDP)

ALTER TABLE panic_events
  ADD COLUMN escalation_level smallint NOT NULL DEFAULT 0,   -- 0 = belum, 1 = semua satpam, 2 = admin
  ADD COLUMN escalated_at     timestamptz;
CREATE INDEX panic_escalation ON panic_events(escalation_level, received_at) WHERE status = 'aktif';

ALTER TABLE users
  ADD COLUMN consent_version text,
  ADD COLUMN consent_at      timestamptz;

-- Riwayat persetujuan (bukti untuk audit; tidak ditimpa)
CREATE TABLE consent_log (
  id         bigserial PRIMARY KEY,
  user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  version    text NOT NULL,
  scopes     text[] NOT NULL DEFAULT '{}',
  agreed_at  timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX consent_log_user ON consent_log(user_id, agreed_at DESC);
