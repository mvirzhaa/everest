-- Everest: skema awal
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  sso_sub       text UNIQUE,
  email         text UNIQUE NOT NULL,
  name          text NOT NULL,
  phone         text,
  role          text NOT NULL DEFAULT 'user' CHECK (role IN ('user','guard','admin')),
  google_refresh_token_enc text,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE devices (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  fcm_token    text NOT NULL UNIQUE,
  model        text,
  last_seen_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX devices_user ON devices(user_id);

-- Catatan: id dibuat di HP (UUID) supaya kirim ulang dari antrean offline tidak membuat data ganda.
CREATE TABLE notes (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title       text NOT NULL DEFAULT '',
  type        text NOT NULL DEFAULT 'pribadi',
  started_at  timestamptz NOT NULL,
  duration_s  integer NOT NULL DEFAULT 0,
  summary     text,
  keywords    text[] NOT NULL DEFAULT '{}',
  audio_path  text,
  updated_at  timestamptz NOT NULL,
  deleted_at  timestamptz
);
CREATE INDEX notes_user_updated ON notes(user_id, updated_at);
CREATE INDEX notes_user_started ON notes(user_id, started_at);

CREATE TABLE note_segments (
  id        uuid PRIMARY KEY,
  note_id   uuid NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
  start_ms  integer NOT NULL,
  end_ms    integer NOT NULL,
  text      text NOT NULL,
  -- konfigurasi 'simple': PostgreSQL belum punya stemmer bahasa Indonesia
  tsv       tsvector GENERATED ALWAYS AS (to_tsvector('simple', text)) STORED
);
CREATE INDEX note_segments_note ON note_segments(note_id, start_ms);
CREATE INDEX note_segments_tsv ON note_segments USING GIN (tsv);

CREATE TABLE emergency_contacts (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  contact_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  name            text NOT NULL,
  phone           text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, contact_user_id)
);

CREATE TABLE guard_duty (
  user_id  uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  on_duty  boolean NOT NULL DEFAULT false,
  since    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE panic_events (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  lat         double precision,
  lng         double precision,
  accuracy_m  real,
  battery     smallint,
  created_at  timestamptz NOT NULL,           -- waktu asli di HP (bisa jauh sebelum diterima saat offline)
  received_at timestamptz NOT NULL DEFAULT now(),
  status      text NOT NULL DEFAULT 'aktif' CHECK (status IN ('aktif','diterima','ditangani','aman','dibatalkan')),
  sms_sent    boolean NOT NULL DEFAULT false,
  acked_by    uuid REFERENCES users(id),
  acked_at    timestamptz,
  resolved_by uuid REFERENCES users(id),
  resolved_at timestamptz,
  note        text
);
CREATE INDEX panic_status ON panic_events(status, created_at DESC);

CREATE TABLE track_sessions (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind        text NOT NULL CHECK (kind IN ('track','panic')),
  panic_id    uuid REFERENCES panic_events(id) ON DELETE SET NULL,
  started_at  timestamptz NOT NULL,
  ended_at    timestamptz,
  share_token text NOT NULL UNIQUE
);
CREATE INDEX track_user ON track_sessions(user_id, started_at DESC);

CREATE TABLE track_recipients (
  session_id        uuid NOT NULL REFERENCES track_sessions(id) ON DELETE CASCADE,
  recipient_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  notified_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (session_id, recipient_user_id)
);

CREATE TABLE location_points (
  id          uuid PRIMARY KEY,
  session_id  uuid NOT NULL REFERENCES track_sessions(id) ON DELETE CASCADE,
  lat         double precision NOT NULL,
  lng         double precision NOT NULL,
  accuracy_m  real,
  speed       real,
  battery     smallint,
  recorded_at timestamptz NOT NULL
);
CREATE INDEX points_session_time ON location_points(session_id, recorded_at);

CREATE TABLE reports (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  photo_path  text NOT NULL,
  description text NOT NULL,
  category    text,
  lat         double precision,
  lng         double precision,
  created_at  timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  status      text NOT NULL DEFAULT 'baru' CHECK (status IN ('baru','diproses','selesai','ditolak')),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reports_user ON reports(user_id, created_at DESC);
CREATE INDEX reports_status ON reports(status, created_at DESC);

CREATE TABLE report_status_log (
  id         bigserial PRIMARY KEY,
  report_id  uuid NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
  status     text NOT NULL,
  note       text,
  actor_id   uuid REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE farhan_usage (
  user_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day      date NOT NULL,
  requests integer NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, day)
);

CREATE TABLE audit_log (
  id         bigserial PRIMARY KEY,
  actor_id   uuid REFERENCES users(id),
  action     text NOT NULL,
  target     text,
  meta       jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
