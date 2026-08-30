import { NavLink, Outlet } from 'react-router-dom';
import { useCapacidades } from '../api/capacidades';

/**
 * A casca desenha o que `/me/capabilities` mandou — e só isso.
 *
 * Não há uma lista de menus no front, nem condicional por perfil. Se o backend parar de
 * devolver o menu de financeiro, ele some daqui sem ninguém tocar neste arquivo. É o que
 * torna verdadeira a promessa de que o front não decide nada.
 */
export function Casca() {
  const { data: capacidades, isPending } = useCapacidades();

  if (isPending) return <Carregando />;
  if (!capacidades) return null;

  return (
    <div style={{ minHeight: '100dvh', display: 'flex', background: 'var(--papel-fundo)' }}>
      <nav aria-label="Seções" style={{ width: 208, flexShrink: 0, background: 'var(--papel)',
        borderRight: '1px solid var(--regua-forte)', display: 'flex', flexDirection: 'column' }}>
        <div style={{ padding: 'var(--e-5) var(--e-4) var(--e-4)' }}>
          <div style={{ fontFamily: 'var(--fonte-identidade)', fontSize: 'var(--t-20)',
            letterSpacing: '-0.01em' }}>{capacidades.estabelecimento.nome}</div>
        </div>

        <ul style={{ listStyle: 'none', margin: 0, padding: '0 var(--e-2)', display: 'flex',
          flexDirection: 'column', gap: '2px' }}>
          {capacidades.menus.map((menu) => (
            <li key={menu.id}>
              <NavLink to={menu.rota} style={({ isActive }) => ({
                display: 'block', padding: 'var(--e-2) var(--e-3)',
                borderRadius: 'var(--raio-2)', fontSize: 'var(--t-14)', textDecoration: 'none',
                color: isActive ? 'var(--papel)' : 'var(--tinta-fraca)',
                background: isActive ? 'var(--tinta)' : 'transparent',
                fontWeight: isActive ? 600 : 400,
              })}>{menu.rotulo}</NavLink>
            </li>
          ))}
        </ul>

        <div style={{ flexGrow: 1 }} />

        <div style={{ padding: 'var(--e-4)', borderTop: '1px solid var(--regua)' }}>
          <div style={{ fontSize: 'var(--t-12)', fontWeight: 600 }}>{capacidades.nome}</div>
          <div style={{ fontSize: 'var(--t-11)', color: 'var(--tinta-fraca)' }}>
            {capacidades.perfil.toLowerCase()}
          </div>
        </div>
      </nav>

      <main style={{ flexGrow: 1, minWidth: 0 }}>
        <Outlet />
      </main>
    </div>
  );
}

function Carregando() {
  // Esqueleto, nunca spinner de tela cheia (R-UX-18): a estrutura aparece na hora
  // e o conteúdo preenche.
  return (
    <div style={{ minHeight: '100dvh', display: 'flex', background: 'var(--papel-fundo)' }}>
      <div style={{ width: 208, background: 'var(--papel)',
        borderRight: '1px solid var(--regua-forte)' }} />
      <div style={{ flexGrow: 1 }} />
    </div>
  );
}
