import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import { ErroDaApi, api } from '../api/http';
import { chaveDasCapacidades, useCapacidades } from '../api/capacidades';
import { definirTokenAtual } from '../sessao/sessao';

interface Inscricao { segredo: string; uriOtpauth: string }
interface Confirmacao { codigos: string[]; aviso: string; sessao: { tokenDeAcesso: string } }

/**
 * Duas situações, uma tela: concluir o login de quem já tem MFA, e inscrever quem é obrigado
 * e ainda não tem. O que distingue é a presença do desafio vindo do login.
 */
export function SegundoFator() {
  const local = useLocation();
  const desafio = (local.state as { desafio?: string } | null)?.desafio;
  return desafio ? <Verificar desafio={desafio} /> : <Inscrever />;
}

function Verificar({ desafio }: { desafio: string }) {
  const navegar = useNavigate();
  const [codigo, definirCodigo] = useState('');
  const [erro, definirErro] = useState<string | null>(null);

  async function verificar() {
    definirErro(null);
    try {
      const r = await api<{ tokenDeAcesso: string }>('/v1/auth/mfa/verificar', {
        method: 'POST',
        body: JSON.stringify({ desafio, codigo }),
      });
      definirTokenAtual(r.tokenDeAcesso);
      navegar('/');
    } catch (e) {
      definirErro(e instanceof ErroDaApi && e.codigo === 'ER-IAM-SEGUNDO_FATOR_INVALIDO'
        ? 'Código inválido ou expirado.'
        : 'Não foi possível verificar agora.');
    }
  }

  return (
    <Moldura titulo="Verificação" descricao="Abra o aplicativo autenticador e informe o código de seis dígitos.">
      <CampoDeCodigo valor={codigo} aoMudar={definirCodigo} aoConfirmar={verificar} />
      {erro && <Alerta>{erro}</Alerta>}
      <Botao onClick={verificar}>Verificar</Botao>
    </Moldura>
  );
}

function Inscrever() {
  const cliente = useQueryClient();
  const navegar = useNavigate();
  const { data: capacidades } = useCapacidades();
  const [inscricao, definirInscricao] = useState<Inscricao | null>(null);
  const [codigo, definirCodigo] = useState('');
  const [codigos, definirCodigos] = useState<string[] | null>(null);
  const [erro, definirErro] = useState<string | null>(null);

  async function comecar() {
    definirInscricao(await api<Inscricao>('/v1/auth/mfa/inscrever', { method: 'POST' }));
  }

  async function confirmar() {
    definirErro(null);
    try {
      const r = await api<Confirmacao>('/v1/auth/mfa/confirmar', {
        method: 'POST', body: JSON.stringify({ codigo }),
      });
      // A confirmação devolve tokens novos: o token em uso ainda diz mfa=false e o backend
      // continuaria bloqueando quem acabou de fazer o que se pediu.
      definirTokenAtual(r.sessao.tokenDeAcesso);
      await cliente.invalidateQueries({ queryKey: chaveDasCapacidades });
      definirCodigos(r.codigos);
    } catch (e) {
      definirErro(e instanceof ErroDaApi && e.codigo === 'ER-IAM-SEGUNDO_FATOR_INVALIDO'
        ? 'Código inválido. Confira o relógio do celular.'
        : 'Não foi possível confirmar agora.');
    }
  }

  if (codigos) {
    return (
      <Moldura titulo="Guarde estes códigos"
        descricao="Cada um funciona uma única vez, e eles não serão exibidos de novo. Se você perder o celular, é por aqui que entra.">
        <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 var(--e-6)',
          display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 'var(--e-2)' }}>
          {codigos.map((c) => (
            <li key={c} style={{ fontFamily: 'var(--fonte-mono)', fontSize: 'var(--t-14)',
              padding: 'var(--e-2)', background: 'var(--papel-afundado)',
              borderRadius: 'var(--raio-1)', textAlign: 'center' }}>{c}</li>
          ))}
        </ul>
        <Botao onClick={() => navegar('/')}>Guardei — continuar</Botao>
      </Moldura>
    );
  }

  if (!inscricao) {
    return (
      <Moldura titulo="Ative o segundo fator"
        descricao={`Seu perfil (${capacidades?.perfil.toLowerCase() ?? ''}) enxerga o financeiro do salão, e por isso o segundo fator é obrigatório.`}>
        <Botao onClick={comecar}>Começar</Botao>
      </Moldura>
    );
  }

  return (
    <Moldura titulo="Ative o segundo fator"
      descricao="No aplicativo autenticador, use este código de configuração. Depois informe os seis dígitos que ele mostrar.">
      <div style={{ fontFamily: 'var(--fonte-mono)', fontSize: 'var(--t-14)',
        letterSpacing: '0.08em', padding: 'var(--e-3)', background: 'var(--papel-afundado)',
        borderRadius: 'var(--raio-1)', wordBreak: 'break-all',
        marginBottom: 'var(--e-5)' }}>{inscricao.segredo}</div>
      <CampoDeCodigo valor={codigo} aoMudar={definirCodigo} aoConfirmar={confirmar} />
      {erro && <Alerta>{erro}</Alerta>}
      <Botao onClick={confirmar}>Confirmar</Botao>
    </Moldura>
  );
}

function Moldura({ titulo, descricao, children }:
  { titulo: string; descricao: string; children: React.ReactNode }) {
  return (
    <main style={{ minHeight: '100dvh', display: 'grid', placeItems: 'center',
      background: 'var(--papel-fundo)', padding: 'var(--e-6)' }}>
      <section style={{ width: 400, background: 'var(--papel)',
        border: '1px solid var(--regua-forte)', borderRadius: 'var(--raio-2)',
        padding: 'var(--e-8)' }}>
        <h1 style={{ fontFamily: 'var(--fonte-identidade)', fontSize: 'var(--t-25)',
          margin: 0, fontWeight: 400 }}>{titulo}</h1>
        <p style={{ fontSize: 'var(--t-12)', color: 'var(--tinta-fraca)',
          margin: 'var(--e-2) 0 var(--e-6)', lineHeight: 1.5 }}>{descricao}</p>
        {children}
      </section>
    </main>
  );
}

function CampoDeCodigo({ valor, aoMudar, aoConfirmar }:
  { valor: string; aoMudar: (v: string) => void; aoConfirmar: () => void }) {
  return (
    <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={valor}
      aria-label="Código de verificação" autoFocus
      onChange={(e) => aoMudar(e.target.value.replace(/\D/g, ''))}
      onKeyDown={(e) => { if (e.key === 'Enter') aoConfirmar(); }}
      style={{ width: '100%', boxSizing: 'border-box', padding: 'var(--e-3)',
        border: '1px solid var(--regua-forte)', borderRadius: 'var(--raio-2)',
        background: 'var(--papel)', fontFamily: 'var(--fonte-mono)', fontSize: 'var(--t-20)',
        letterSpacing: '0.28em', textAlign: 'center', marginBottom: 'var(--e-4)' }} />
  );
}

function Alerta({ children }: { children: React.ReactNode }) {
  return <p role="alert" style={{ fontSize: 'var(--t-12)', color: 'var(--status-falta)',
    margin: '0 0 var(--e-4)' }}>{children}</p>;
}

function Botao({ onClick, children }: { onClick: () => void; children: React.ReactNode }) {
  return (
    <button onClick={onClick} style={{ width: '100%', border: 0,
      borderRadius: 'var(--raio-2)', padding: 'var(--e-3)', background: 'var(--tinta)',
      color: 'var(--papel)', fontFamily: 'inherit', fontSize: 'var(--t-14)',
      fontWeight: 600, cursor: 'pointer' }}>{children}</button>
  );
}
