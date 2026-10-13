import json, sys
from collections import defaultdict

def load_lances(path):
    lances = []
    with open(path) as f:
        for line in f:
            lances.append(json.loads(line))
    return lances

def auditoria_financeira(lances):
    rotas = defaultdict(list)
    for d in lances:
        envio = d.get('envio', {})
        rota_id = envio.get('rota_id', 'sem-rota')
        lance = envio.get('lance', {})
        rotas[rota_id].append({
            'd': d,
            'alvo': int(lance.get('alvo_wei_por_gas', '0') or 0),
            'ofertado': int(lance.get('ofertado_total_wei_por_gas', '0') or 0),
            'pagamento': int(lance.get('pagamento_direto_builder_bnb_wei', '0') or 0),
            'limitado_lucro': lance.get('limitado_por_lucro', False),
            'limitado_saldo': lance.get('limitado_por_saldo', False),
            'lucro_previsto': int(lance.get('lucro_previsto_ativo_wei', '0') or 0),
            'desfecho': d.get('desfecho', {}).get('classe', 'desconhecido'),
            'submissao_aceita': envio.get('submissao_aceita', False),
            'relay_aceitou': envio.get('relay_aceitou', False),
            'saida_prevista': int(lance.get('saida_prevista_wei', '0') or 0),
            'montante_entrada': int(lance.get('montante_entrada_wei', '0') or 0),
            'preco_gas_proprio': int(lance.get('preco_gas_proprio_wei_por_gas', '0') or 0),
            'estimativa_gas': int(lance.get('estimativa_gas_para_bid', '0') or 0),
        })
    
    # Identificar vencedores
    vencedores = []
    perdedores = []
    for rota_id, lance_list in rotas.items():
        for lance in lance_list:
            if lance['desfecho'] == 'incluida' or lance['submissao_aceita'] or lance['relay_aceitou']:
                vencedores.append((rota_id, lance))
            else:
                perdedores.append((rota_id, lance))
    
    print("="*72)
    print("  AUDITORIA FINANCEIRA - LANÇES INCLUÍDOS (vencedores)")
    print("="*72)
    
    print(f"\n1. QUNTO OFERGUEI:")
    alvos = [l['alvo'] for _, l in vencedores if l['alvo'] > 0]
    ofertados = [l['ofertado'] for _, l in vencedores if l['ofertado'] > 0]
    total_alvo = sum(alvos)
    total_ofertado = sum(ofertados)
    print(f"   • Alvo/gas total: {total_alvo:,} wei = {total_alvo/1e9:.2f} Gwei")
    print(f"   • Ofertado/gas total: {total_ofertado:,} wei = {total_ofertado/1e9:.2f} Gwei")
    print(f"   • Diferença alvo - oferta: {total_alvo - total_ofertado:,} wei")
    if total_alvo > 0:
        print(f"   • Oferta/Alvo: {total_ofertado/total_alvo*100:.1f}%")
    
    print(f"\n2. QUEM LEVOU (rota_id):")
    for i, (rota_id, lance) in enumerate(vencedores, 1):
        print(f"   {i:>3}. {rota_id[:38]}")
    
    print(f"\n3. QUANTO PAGUEI:")
    pagamentos = [l['pagamento'] for _, l in vencedores if l['pagamento'] > 0]
    total_pagamento = sum(pagamentos)
    print(f"   • Pagamento total: {total_pagamento:,} wei = {total_pagamento/1e18:.4f} BNB")
    print(f"   • Pagamento médio: {total_pagamento/len(pagamentos):,.0f} wei/gas")
    
    print(f"\n4. ONDE FIQUEI DE FORA:")
    # Diferença de lance com o rival
    comparativos = []
    for rota_id, lance_list in rotas.items():
        vencedor = [l for l in lance_list if l['desfecho'] == 'incluida' or l['submissao_aceita'] or l['relay_aceitou']]
        perdedor = [l for l in lance_list if not (l['desfecho'] == 'incluida' or l['submissao_aceita'] or l['relay_aceitou'])]
        if vencedor and perdedor:
            v = max(vencedor, key=lambda l: l['pagamento'])
            p = max(perdedor, key=lambda l: l['pagamento'])
            if v['pagamento'] > 0 and p['pagamento'] > 0:
                dif = v['pagamento'] - p['pagamento']
                if dif > 0:
                    comparativos.append((dif, rota_id, v, p))
    comparativos.sort(key=lambda x: -x[0])
    print(f"   • Comparação com rival (lances perdedores vs vencedores):")
    total_diferenca = 0
    for i, (dif, rota_id, v, p) in enumerate(comparativos, 1):
        print(f"     {i}. ROTA {rota_id[:38]}: vencedor pag={v['pagamento']:,} vs perdedor pag={p['pagamento']:,} | diferença={dif:,} ({dif/1e9:.2f} gwei)")
        total_diferenca += dif
    print(f"     Total: {total_diferenca:,} wei ficaram de fora")
    
    # Lances recebidos mas descartados (rival_n1, rival_n2)
    derrotas = [l for _, l in perdedores if l['desfecho'] in ('rival_n1', 'rival_n2')]
    print(f"   • Lances derrotados por rival (rival_n1/n2): {len(derrotas)}")
    print(f"   • Lances recusados pelo relay: {len(perdedores) - len(derrotas)}")
    
    print(f"\n5. PODERIAMOS PAGAR MAIS?")
    # Lances limitados
    limitados = [(r, l) for r, l in vencedores if l['limitado_lucro'] or l['limitado_saldo']]
    normal = [(r, l) for r, l in vencedores if not l['limitado_lucro'] and not l['limitado_saldo']]
    print(f"   • Lances limitados por lucro/saldo: {len(limitados)}/{len(vencedores)} ({len(limitados)/len(vencedores)*100:.1f}%)")
    print(f"   • Lances normais: {len(normal)}")
    
    # Lances com lucro_previsto > 0 mas limitados
    lucro_pos = [(r, l) for r, l in vencedores if l['lucro_previsto'] > 0 and (l['limitado_lucro'] or l['limitado_saldo'])]
    print(f"   • Lances com lucro_previsto > 0 mas limitados: {len(lucro_pos)}")
    
    # Lances com alvo=0
    zeros = [(r, l) for r, l in vencedores if l['alvo'] == 0]
    print(f"   • Lances com alvo=0 (lance zerado): {len(zeros)}")
    for i, (r, l) in enumerate(zeros[:5], 1):
        print(f"     {i}. {r[:38]}: ofertado={l['ofertado']:,} pag={l['pagamento']:,} stopo={l['saida_prevista']:,} (lucro_previsto=0)")
    
    # O que poderíamos pagar mais
    print(f"\n   Análise de margem:")
    print(f"   • Se o alvo fosse aumentado em X%, o pagamento aumentaria em X%:")
    print(f"     - 10% a mais: {total_pagamento*1.10/1e18:.4f} BNB")
    print(f"     - 20% a mais: {total_pagamento*1.20/1e18:.4f} BNB")
    print(f"     - 30% a mais: {total_pagamento*1.30/1e18:.4f} BNB")
    print(f"   • O lance médio era de {(total_alvo/len(vencedores))/1e9:.2f} gwei/gas")
    print(f"   • O pagamento médio era de {total_pagamento/len(vencedores)/1e9:.2f} gwei/gas")
    print(f"   • Diferença média: {(total_alvo/len(vencedores) - total_pagamento/len(vencedores))/1e9:.2f} gwei/gas")

if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else '/opt/node/target/ensaio.jsonl'
    lances = load_lances(path)
    auditoria_financeira(lances)
