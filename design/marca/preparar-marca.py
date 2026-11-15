#!/usr/bin/env python3
"""Prepara a marca Nexo para o sistema, a partir das duas artes originais — RT-INF-014.

Originais (fonte de verdade, nunca editados):
  design/marca/original/nexo-icone.png        1254², a placa com o N em fita
  design/marca/original/nexo-assinatura.png   2172×724, o N e o nome "nexo"

O que este script faz, e por quê:
  1. ÍCONE "any" — o N sobre a placa de GRAFITE da moldura, cantos transparentes. Em
     15/11/2026 o dono vetou fundo azul, e o azul-noite com brilho de borda da arte é a cara
     de sistema gerado por IA: o N fica, o fundo e o brilho saem.
  2. N ISOLADO — tira o fundo do N pela luminância. Serve para recompor o ícone onde o N
     precisa ser menor que na arte (abaixo).
  3. ÍCONE "maskable" — o Android recorta o ícone num círculo de 80%. Na arte o N ocupa a
     placa quase inteira e perderia as pontas; aqui ele é recomposto no azul-noite da marca,
     dentro da zona segura.
  4. ASSINATURA — "N + nexo" sem o retângulo preto e sem os arcos de luz, para pousar em
     qualquer fundo escuro do sistema.

Requer Pillow e numpy (ferramenta de design, não dependência do produto):
  pip install pillow numpy
"""
import pathlib

import numpy as np
from PIL import Image, ImageDraw

RAIZ = pathlib.Path(__file__).resolve().parents[2]
ORIGINAL = RAIZ / 'design/marca/original'
PUBLICO = RAIZ / 'frontend/public'
APP = RAIZ / 'frontend/src/assets/marca'
MARCA = RAIZ / 'design/marca'

NOITE = (7, 4, 38)     # o fundo da arte, medido no centro da placa — só para tirar o N dele
GRAFITE = (28, 28, 30)  # --espelho: a moldura do sistema, e o fundo de todo ícone


def mascara_arredondada(lado, raio, amostra=4):
    """Canto antisserrilhado: desenha 4× maior e reduz."""
    grande = Image.new('L', (lado * amostra, lado * amostra), 0)
    ImageDraw.Draw(grande).rounded_rectangle(
        (0, 0, lado * amostra - 1, lado * amostra - 1), radius=raio * amostra, fill=255)
    return grande.resize((lado, lado), Image.LANCZOS)


def sem_fundo(img, piso, teto, caixa=None, so_uma_peca=False):
    """Alfa pela luminância (canal mais forte): abaixo de `piso` é fundo, acima de `teto` é
    desenho. A cor é desmultiplicada do fundo escuro — sem isso a borda do N carrega um halo
    azul-noite que aparece em qualquer fundo mais claro que a arte."""
    rgb = np.asarray(img.convert('RGB')).astype(np.float32)
    alfa = np.clip((rgb.max(axis=2) - piso) / (teto - piso), 0, 1)
    if caixa:
        x0, y0, x1, y1 = caixa
        fora = np.ones_like(alfa, dtype=bool)
        fora[y0:y1, x0:x1] = False
        alfa[fora] = 0
    fundo = np.array(NOITE, dtype=np.float32)
    seguro = np.maximum(alfa, 1e-3)[..., None]
    cor = np.clip((rgb - fundo * (1 - alfa[..., None])) / seguro, 0, 255)
    if so_uma_peca:  # o N é uma peça só; a assinatura tem cinco (o N e as quatro letras)
        alfa = so_o_desenho(alfa)
    rgba = np.dstack([cor, alfa * 255]).astype(np.uint8)
    out = Image.fromarray(rgba, 'RGBA')
    return out.crop(out.getbbox())


def so_o_desenho(alfa):
    """Fica só o que está ligado ao desenho principal.

    Um grão de brilho da arte, solto perto do N, passava pelo limiar e virava um ponto escuro
    ao lado dele no login. Reconstrução geodésica: parte do pixel mais opaco e cresce só por
    dentro da máscara — o que não se liga a ele (o grão) fica de fora.
    """
    mascara = alfa > 0.02
    semente = np.zeros_like(mascara)
    semente[np.unravel_index(np.argmax(alfa), alfa.shape)] = True
    atual = semente
    while True:
        cresce = atual.copy()
        cresce[1:, :] |= atual[:-1, :]
        cresce[:-1, :] |= atual[1:, :]
        cresce[:, 1:] |= atual[:, :-1]
        cresce[:, :-1] |= atual[:, 1:]
        cresce &= mascara
        if (cresce == atual).all():
            break
        atual = cresce
    return np.where(atual, alfa, 0)


def em_tela(desenho, lado, ocupa, fundo=NOITE):
    """O desenho centrado num quadrado de cor `fundo`, cabendo em `ocupa` da diagonal."""
    tela = Image.new('RGBA', (lado, lado), fundo + (255,))
    w, h = desenho.size
    escala = (ocupa * lado) / (w * w + h * h) ** 0.5
    d = desenho.resize((round(w * escala), round(h * escala)), Image.LANCZOS)
    tela.alpha_composite(d, ((lado - d.width) // 2, (lado - d.height) // 2))
    return tela


def main():
    APP.mkdir(parents=True, exist_ok=True)
    ico = Image.open(ORIGINAL / 'nexo-icone.png').convert('RGB')
    ass = Image.open(ORIGINAL / 'nexo-assinatura.png').convert('RGB')

    # 2. o N isolado (da arte do ícone, a de maior resolução do N)
    n = sem_fundo(ico, piso=70, teto=125, caixa=(185, 290, 1075, 945), so_uma_peca=True)
    n.save(MARCA / 'nexo-n.png', optimize=True)
    # Para a porta do sistema, em WebP: o mesmo desenho a 1/6 do peso do PNG, e é a primeira
    # imagem que quem abre o app baixa.
    n.save(APP / 'nexo-n.webp', quality=86, method=6)

    # 1. "any": o N na placa de grafite, cantos de 96/432 (o arredondado dos lançadores),
    #    40px de folga em 512 para conviver com os ícones do sistema operacional
    corpo = em_tela(n, 432, 0.82, GRAFITE)
    corpo.putalpha(mascara_arredondada(432, 96))
    qualquer = Image.new('RGBA', (512, 512), (0, 0, 0, 0))
    qualquer.alpha_composite(corpo, (40, 40))
    qualquer.save(PUBLICO / 'pwa-icon-512.png', optimize=True)
    qualquer.resize((192, 192), Image.LANCZOS).save(PUBLICO / 'pwa-icon-192.png', optimize=True)
    qualquer.resize((48, 48), Image.LANCZOS).save(PUBLICO / 'favicon-48.png', optimize=True)
    qualquer.resize((256, 256), Image.LANCZOS).save(APP / 'nexo-icone.png', optimize=True)

    # 3. "maskable" e apple-touch: o N no grafite, dentro da zona segura
    em_tela(n, 512, 0.74, GRAFITE).save(PUBLICO / 'pwa-maskable-512.png', optimize=True)
    # O iOS recorta em cantos arredondados, não em círculo: o N pode ser maior
    em_tela(n, 180, 0.86, GRAFITE).convert('RGB').save(PUBLICO / 'apple-touch-icon.png', optimize=True)

    # 4. a assinatura sem fundo, na altura de uso ×3 (aparece com ~48px no sistema)
    assinatura = sem_fundo(ass, piso=55, teto=120, caixa=(250, 150, 1910, 600))
    alvo = 156
    assinatura = assinatura.resize((round(assinatura.width * alvo / assinatura.height), alvo),
                                   Image.LANCZOS)
    assinatura.save(APP / 'nexo-assinatura.png', optimize=True)
    assinatura.save(MARCA / 'nexo-assinatura-sem-fundo.png', optimize=True)

    print(f'N {n.size} · assinatura {assinatura.size}')


if __name__ == '__main__':
    main()
