# NorthKaiCam · GCam SRK AIO para o Mi 11 Ultra

Mod da GCam **SRK AIO** para o **Xiaomi Mi 11 Ultra** (haydn) que usa a **tela traseira** como visor, do mesmo jeito que a câmera nativa da Xiaomi.

> Testado no Mi 11 Ultra com ROM baseada em HyperOS 3 (Android 14).

## ✨ O que tem de novo

| Recurso | Descrição |
|---|---|
| 📷 **Preview na tela traseira** | Botão ▣ na câmera espelha o visor na telinha de 1,1" (126×294). |
| ⚡ **Acorda a telinha sozinho** | Usa a mesma chamada de sistema da câmera da Xiaomi (`CAMERA_CALL`). Não precisa tocar duas vezes na tela traseira. |
| 🌑 **Apaga ao desligar** | Ao desligar o preview ou sair da câmera, a telinha apaga (não volta pro relógio). |
| 🔘 **Só liga quando você quer** | A câmera abre com a telinha desligada; um toque no ▣ liga. |
| 📁 **Nova pasta de configurações** | XMLs em `/sdcard/Download/Northkai/xml/` (antes `/HyperCam/Strike/`). |

### Menu do botão (toque longo no ▣)

- **Espelhar** (ligado por padrão, ideal pra selfie com a câmera principal)
- **Girar 90°**
- **Ajustar / Preencher**
- **Mover botão**
- **Escolher display** (automático por padrão)
- **Info dos displays** (mostra os displays e um log de diagnóstico)
- **Ligar automaticamente** ao abrir a câmera

## 📥 Instalação

1. Baixe o APK na aba [**Releases**](../../releases).
2. Se você tem outra SRK AIO instalada com assinatura diferente, **desinstale antes** (o pacote é o mesmo: `com.google.android.srkaiohcam`).
3. Instale e abra a câmera.
4. Coloque seus XMLs em `Download/Northkai/xml/`.

## 🔧 Como funciona

A tela traseira do Mi 11 Ultra é um display separado (`displayId 2`, `FLAG_PRESENTATION`) em um **grupo de energia próprio**. Por isso, acordar o celular não acorda a telinha.

A câmera da Xiaomi (`Display.switchPresentationDisplay`) resolve isso com uma transação escondida no serviço `power`:

| Ação | Código | Dados |
|---|---|---|
| Acordar telinha | `0xFFFFFA` | token `Binder`, `uptimeMillis`, `1`, `"CAMERA_CALL"` |
| Apagar telinha | `0xFFFFFB` | `uptimeMillis`, `"CAMERA_CALL"` |
| Devolver pro relógio | `0xFFFFF8` | token `Binder`, `1` |

O mod repete exatamente essa chamada e depois abre uma `Presentation` no display traseiro. O visor da GCam (um `TextureView`) é copiado ~30×/s, já na resolução da telinha, então o custo é baixo.

Log do sistema confirmando:

```
PowerManagerServiceImpl: Waking up secondary display from: 10360, reason: CAMERA_CALL
```

## 🏗️ Compilar você mesmo

O repositório **não** contém o APK da SRK AIO, só o código novo e o patch.

```bash
# requisitos: JDK 17+, python3, apktool.jar, android.jar (API 28+), dex-tools (dex2jar)
scripts/build.sh SRK_AIO_original.apk minha_chave.jks meu_alias
```

Estrutura:

```
src/com/caio/rear/RearPreview.java   # botão, Presentation, captura do visor, wake da telinha
patches/srk-aio-northkai.patch       # hooks onResume/onPause + nova pasta de XML
scripts/build.sh                     # monta o APK a partir da SRK AIO original
scripts/zipalign.py                  # zipalign em Python (sem SDK)
scripts/install_and_log.bat          # Windows: instala via ADB e coleta logs
```

## 🙏 Créditos

- **SRK AIO / HyperCam**: autores originais do mod da GCam.
- **Google Camera**: Google.
- Tela traseira e pasta Northkai: **Northkai** (Caio Dantas).

## ⚠️ Aviso

Projeto pessoal e não oficial, sem relação com Google ou Xiaomi. Use por sua conta e risco.
