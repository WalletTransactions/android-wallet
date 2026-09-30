-- Exported from QuickDBD: https://www.quickdatatabasediagrams.com/
-- Link to schema: https://app.quickdatabasediagrams.com/#/d/4r8miC
-- NOTE! If you have used non-SQL datatypes in your design, you will have to change these here.

-- Modify this code to update the DB schema diagram.
-- To reset the sample schema, replace everything with
-- two dots ('..' - without quotes).

CREATE TABLE "accounts" (
    "id" INTEGER  NOT NULL ,
    "name" TEXT  NOT NULL ,
    "ico_name" TEXT  NOT NULL ,
    "comment" TEXT  NOT NULL ,
    "categroy_type" INTEGER  NOT NULL ,
    CONSTRAINT "pk_accounts" PRIMARY KEY (
        "id"
    )
)

GO

CREATE TABLE "account_categories" (
    "id" INTEGER  NOT NULL ,
    "name" TEXT  NOT NULL ,
    CONSTRAINT "pk_account_categories" PRIMARY KEY (
        "id"
    )
)

GO

CREATE TABLE "currency" (
    "Id" INTEGER  NOT NULL ,
    "iso_code" TEXT  NOT NULL ,
    "ico_name" text  NOT NULL ,
    CONSTRAINT "pk_currency" PRIMARY KEY (
        "Id"
    )
)

GO

CREATE TABLE "operating_account" (
    "id" INTEGER  NOT NULL ,
    "currency_id" INTEGER  NOT NULL ,
    CONSTRAINT "pk_operating_account" PRIMARY KEY (
        "id"
    )
)

GO

CREATE TABLE "transactions" (
    "id" INTEGER  NOT NULL ,
    "date" datetime  NOT NULL ,
    "acc_fr_id" INTEGER  NOT NULL ,
    "acc_to_id" INTEGER  NOT NULL ,
    "amount_rc" DECIMAL(5,2)  NOT NULL ,
    "amount_fr" DECIMAL(5,2)  NOT NULL ,
    "amount_to" DECIMAL(5,2)  NOT NULL ,
    "comment" TEXT  NOT NULL ,
    CONSTRAINT "pk_transactions" PRIMARY KEY (
        "id"
    )
)

GO

CREATE TABLE "template" (
    "id" INTEGER  NOT NULL ,
    "Name" Text  NOT NULL ,
    CONSTRAINT "pk_template" PRIMARY KEY (
        "id"
    )
)

GO

CREATE TABLE "template_transactions" (
    "id" INTEGER  NOT NULL ,
    "template_id" INTEGER  NOT NULL ,
    "acc_from_id" INTEGER  NOT NULL ,
    "acc_to_id" INTEGER  NOT NULL ,
    "amount_rc" DECIMAL(5,2)  NOT NULL ,
    "amount_fr" DECIMAL(5,2)  NOT NULL ,
    "amount_to" DECIMAL(5,2)  NOT NULL ,
    "comment" TEXT  NOT NULL ,
    CONSTRAINT "pk_template_transactions" PRIMARY KEY (
        "id"
    )
)

GO

CREATE TABLE "settings" (
    "reporting_currency" INT  NOT NULL 
)

GO

ALTER TABLE "accounts" ADD CONSTRAINT "fk_accounts_categroy_type" FOREIGN KEY("categroy_type")
REFERENCES "account_categories" ("id")
GO

ALTER TABLE "operating_account" ADD CONSTRAINT "fk_operating_account_id" FOREIGN KEY("id")
REFERENCES "accounts" ("id")
GO

ALTER TABLE "operating_account" ADD CONSTRAINT "fk_operating_account_currency_id" FOREIGN KEY("currency_id")
REFERENCES "currency" ("Id")
GO

ALTER TABLE "transactions" ADD CONSTRAINT "fk_transactions_acc_fr_id" FOREIGN KEY("acc_fr_id")
REFERENCES "accounts" ("id")
GO

ALTER TABLE "transactions" ADD CONSTRAINT "fk_transactions_acc_to_id" FOREIGN KEY("acc_to_id")
REFERENCES "accounts" ("id")
GO

ALTER TABLE "template_transactions" ADD CONSTRAINT "fk_template_transactions_template_id" FOREIGN KEY("template_id")
REFERENCES "template" ("id")
GO

ALTER TABLE "template_transactions" ADD CONSTRAINT "fk_template_transactions_acc_from_id" FOREIGN KEY("acc_from_id")
REFERENCES "accounts" ("id")
GO

ALTER TABLE "template_transactions" ADD CONSTRAINT "fk_template_transactions_acc_to_id" FOREIGN KEY("acc_to_id")
REFERENCES "accounts" ("id")
GO

ALTER TABLE "settings" ADD CONSTRAINT "fk_settings_reporting_currency" FOREIGN KEY("reporting_currency")
REFERENCES "currency" ("Id")
GO

CREATE INDEX "idx_accounts_name"
ON "accounts" ("name")
GO

CREATE INDEX "idx_currency_iso_code"
ON "currency" ("iso_code")
GO

CREATE INDEX "idx_template_Name"
ON "template" ("Name")
GO

