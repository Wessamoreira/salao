import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import { ErroDaApi, api } from '../api/http';
import { chaveDasCapacidades, useCapacidades } from '../api/capacidades';
import { definirTokenAtual } from '../sessao/sessao';
import { Autenticacao } from './Autenticacao';

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
        <ul className="auth-recuperacao">
          {codigos.map((c) => (
            <li key={c}>{c}</li>
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
      <div className="auth-segredo">{inscricao.segredo}</div>
      <CampoDeCodigo valor={codigo} aoMudar={definirCodigo} aoConfirmar={confirmar} />
      {erro && <Alerta>{erro}</Alerta>}
      <Botao onClick={confirmar}>Confirmar</Botao>
    </Moldura>
  );
}

function Moldura({ titulo, descricao, children }:
  { titulo: string; descricao: string; children: React.ReactNode }) {
  return (
    <Autenticacao titulo={titulo} apoio={descricao}>
      <div className="auth-formulario">{children}</div>
    </Autenticacao>
  );
}

function CampoDeCodigo({ valor, aoMudar, aoConfirmar }:
  { valor: string; aoMudar: (v: string) => void; aoConfirmar: () => void }) {
  return (
    <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={valor}
      className="auth-codigo" aria-label="Código de verificação" autoFocus
      onChange={(e) => aoMudar(e.target.value.replace(/\D/g, ''))}
      onKeyDown={(e) => { if (e.key === 'Enter') aoConfirmar(); }} />
  );
}

function Alerta({ children }: { children: React.ReactNode }) {
  return <p className="auth-erro" role="alert">{children}</p>;
}

function Botao({ onClick, children }: { onClick: () => void; children: React.ReactNode }) {
  return (
    <button className="auth-acao" type="button" onClick={onClick}>{children}</button>
  );
}
