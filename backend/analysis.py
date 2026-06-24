"""Calcul du prix de référence et du classement (côté serveur).

Mêmes règles que l'application :
- moyenneOffres = somme(offres retenues) / nombre(offres retenues)
- prixReference = (estimation + moyenneOffres) / 2
- ecartDh = |offre - prixReference| ; ecartPercent = ecartDh / prixReference * 100
- classement = tri par ecartDh croissant
"""

from __future__ import annotations


def _observation(ecart_percent: float) -> str:
    if ecart_percent <= 1.0:
        return "Très proche"
    if ecart_percent <= 3.0:
        return "Proche"
    if ecart_percent <= 7.0:
        return "Moyen"
    return "Éloigné"


def _is_retenue(statut: str) -> bool:
    s = (statut or "").lower()
    return not any(k in s for k in ("ecart", "écart", "rejet", "exclu", "elimin"))


def compute_analyse(estimation: float, lots: list[dict]) -> dict:
    """Calcule l'analyse à partir de l'estimation et des offres des lots."""
    # On agrège les offres de tous les lots (le modèle actuel est mono-lot).
    offres: list[dict] = []
    for lot in lots:
        offres.extend(lot.get("offres", []))

    retenues = [o for o in offres if _is_retenue(o.get("statut", "retenue"))
                and float(o.get("montant", 0) or 0) > 0]
    nb_ecartees = len(offres) - len(retenues)

    est = float(estimation or 0)
    if est <= 0 or not retenues:
        return {
            "calculable": False,
            "estimation": est,
            "moyenneOffres": 0.0,
            "prixReference": 0.0,
            "nbRetenues": len(retenues),
            "nbEcartees": nb_ecartees,
            "gagnantProbable": None,
            "classement": [],
            "message": ("Les informations générales ont été récupérées, mais les "
                        "montants nécessaires au calcul (estimation et offres "
                        "retenues) ne sont pas disponibles. Complétez les données "
                        "manuellement."),
        }

    moyenne = sum(float(o["montant"]) for o in retenues) / len(retenues)
    prix_ref = (est + moyenne) / 2.0

    classees = []
    for o in retenues:
        montant = float(o["montant"])
        ecart = abs(montant - prix_ref)
        ecart_pct = (ecart / prix_ref * 100.0) if prix_ref else 0.0
        classees.append({
            "societe": o.get("societe", ""),
            "montant": montant,
            "statut": o.get("statut", "retenue"),
            "ecartDh": ecart,
            "ecartPercent": ecart_pct,
            "observation": _observation(ecart_pct),
        })
    classees.sort(key=lambda x: (x["ecartDh"], x["montant"]))
    for i, c in enumerate(classees, start=1):
        c["rang"] = i

    return {
        "calculable": True,
        "estimation": est,
        "moyenneOffres": moyenne,
        "prixReference": prix_ref,
        "nbRetenues": len(retenues),
        "nbEcartees": nb_ecartees,
        "gagnantProbable": classees[0]["societe"] if classees else None,
        "classement": classees,
        "message": f"{len(retenues)} offre(s) retenue(s). Gagnant probable : "
                   f"{classees[0]['societe'] if classees else '—'}.",
    }
