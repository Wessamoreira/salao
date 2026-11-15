import { useMemo } from 'react';
import qrcode from 'qrcode-generator';

/**
 * Um QR code desenhado aqui, no navegador — RT-INF-017.
 *
 * <h2>Por que não um serviço de QR</h2>
 *
 * O conteúdo do QR do segundo fator É o segredo dele (`otpauth://…?secret=…`). Mandá-lo a um
 * gerador na internet entregaria a terceiros a chave que protege o financeiro do salão. A
 * matriz é calculada pela `qrcode-generator` (MIT, sem dependências) e desenhada pelo React.
 *
 * <h2>Por que `<path>`, e não `innerHTML` com o SVG pronto da biblioteca</h2>
 *
 * Nenhum HTML por string no sistema: é o que permite exigir Trusted Types na CSP (RT-INF-015).
 *
 * <h2>Preto sobre branco, com margem de 4 módulos</h2>
 *
 * Não é a paleta do sistema, e de propósito: leitor de QR precisa de contraste máximo e da "zona
 * quieta" em volta. QR invertido (claro sobre escuro) falha em parte dos aplicativos.
 */
export function QrCode({ texto, tamanho = 200, rotulo }: {
  texto: string;
  tamanho?: number;
  /** O que o código é, para quem não vê a imagem. */
  rotulo: string;
}) {
  const { caminho, lado } = useMemo(() => {
    // Tipo 0 = o menor que couber; correção M (15%) aguenta reflexo e tela suja.
    const qr = qrcode(0, 'M');
    qr.addData(texto);
    qr.make();
    const modulos = qr.getModuleCount();
    const margem = 4;
    let d = '';
    for (let linha = 0; linha < modulos; linha += 1) {
      for (let coluna = 0; coluna < modulos; coluna += 1) {
        if (qr.isDark(linha, coluna)) d += `M${coluna + margem} ${linha + margem}h1v1h-1z`;
      }
    }
    return { caminho: d, lado: modulos + margem * 2 };
  }, [texto]);

  return (
    <svg role="img" aria-label={rotulo} width={tamanho} height={tamanho}
      viewBox={`0 0 ${lado} ${lado}`} shapeRendering="crispEdges">
      <rect width={lado} height={lado} fill="#ffffff" />
      <path d={caminho} fill="#000000" />
    </svg>
  );
}
