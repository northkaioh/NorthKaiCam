# Changelog

## v7
- Compatibilidade com ports Android 15, 16 e 17: o comando que acorda a tela traseira agora é feito
  pelo próprio código da câmera da ROM instalada (`IMiuiSubScreenManager`), com os códigos daquela versão.
- Se a câmera da ROM não tiver esse código, usa a transação manual (códigos do HyperOS do Mi 11 Ultra).
- Se a telinha não acender em 1,5 s, tenta automaticamente pelo outro caminho.
- Escolha do display mais robusta (prefere display físico de apresentação).

## v6
- Pasta de configurações movida para `/sdcard/Download/Northkai/xml/`.

## v5
- A câmera abre com a tela traseira desligada; liga só pelo botão ▣.
- Ao desligar, a telinha apaga (`0xFFFFFB`) em vez de voltar pro relógio.

## v4
- Acorda a tela traseira com a mesma chamada da câmera da Xiaomi (`CAMERA_CALL`, `0xFFFFFA`).

## v3
- Log de diagnóstico em "Info dos displays".

## v1–v2
- Botão ▣ com preview da GCam na tela traseira via `Presentation`.
- Menu: espelhar, girar, ajustar/preencher, mover botão, escolher display.
