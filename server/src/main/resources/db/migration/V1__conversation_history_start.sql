ALTER TABLE dbo.conversation
    ADD COLUMN firstmessageat timestamp with time zone;

ALTER TABLE dbo.conversation
    ADD COLUMN historystartreached boolean NOT NULL DEFAULT false;