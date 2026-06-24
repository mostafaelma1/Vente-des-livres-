"""Schémas Pydantic du backend PrixRef AO."""

from __future__ import annotations

from pydantic import BaseModel, Field


class AnalyseUrlRequest(BaseModel):
    url: str = Field(..., description="URL de suivi de consultation marchespublics.gov.ma")


class Offre(BaseModel):
    societe: str = ""
    montant: float = 0.0
    statut: str = "retenue"  # "retenue" | "ecartee"


class Lot(BaseModel):
    numero: str = "1"
    designation: str = ""
    estimation: float = 0.0
    offres: list[Offre] = Field(default_factory=list)


class Consultation(BaseModel):
    reference: str = ""
    objet: str = ""
    acheteur: str = ""
    lieuExecution: str = ""
    categorie: str = ""
    procedure: str = ""
    estimation: float = 0.0
    caution: float = 0.0


class RawTable(BaseModel):
    index: int = 0
    headers: list[str] = Field(default_factory=list)
    rows: list[list[str]] = Field(default_factory=list)


class OffreClassee(BaseModel):
    rang: int = 0
    societe: str = ""
    montant: float = 0.0
    statut: str = "retenue"
    ecartDh: float = 0.0
    ecartPercent: float = 0.0
    observation: str = ""


class Analyse(BaseModel):
    calculable: bool = False
    estimation: float = 0.0
    moyenneOffres: float = 0.0
    prixReference: float = 0.0
    nbRetenues: int = 0
    nbEcartees: int = 0
    gagnantProbable: str | None = None
    classement: list[OffreClassee] = Field(default_factory=list)
    message: str = ""


class AnalyseUrlResponse(BaseModel):
    """Réponse unifiée (succès ou échec)."""

    success: bool = False
    errorCode: str | None = None
    message: str = ""
    refConsultation: str = ""
    orgAcronyme: str = ""
    sourceUrl: str = ""
    consultation: Consultation = Field(default_factory=Consultation)
    lots: list[Lot] = Field(default_factory=list)
    analyse: Analyse | None = None
    tables: list[RawTable] = Field(default_factory=list)
    rawText: str = ""
