# Piano Orchestra

Application Android pour tablette, reliée en USB au piano numérique Kawai ES-520.
Bermet joue, la tablette joue l'orchestre. Interface en français et en russe.

## Ce que fait cette version (0.1)

- **Accueil** : bouton français / русский, état du piano (connecté ou non), trois grandes portes.
- **Clavier et test** : clavier de 88 touches qui s'allume quand on joue, journal des notes reçues,
  option pour faire sonner la tablette avec un autre son (cordes, chœur, orgue…).
- **Jouer avec un groupe** : six morceaux fournis (composés avec Riffmaker) et import de fichiers MIDI.
  On choisit « ma partie » (coupée, affichée en doré sur le clavier), la tablette joue le reste.
  Tempo de 50 à 120 %, décompte, mixeur (volume, couper, solo) par instrument.
- **Réglages** : volume, sortie audio (haut-parleurs, prise vers le Line In du piano, Bluetooth), mises à jour.
- **Hors ligne** : tout est sur la tablette ; internet ne sert qu'aux mises à jour.

## Comment c'est construit

- Kotlin et Jetpack Compose, Android 12 minimum.
- Entrée MIDI : API Android `android.media.midi` (USB, reconnexion automatique).
- Son : synthétiseur SoundFont écrit pour l'app (`audio/Synth.kt`), banque GeneralUser GS.
- Chaque envoi sur `main` : GitHub compile l'APK, lance les tests du moteur (rendu audio réel),
  essaie l'app sur une tablette virtuelle (captures en français et en russe), puis publie la version.
  L'application installée propose la mise à jour toute seule.

Sons : GeneralUser GS v2.0.3, S. Christian Collins — voir `app/src/main/assets/sounds_LICENSE.txt`.
