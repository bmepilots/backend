"""Rebuild original study content. Python standard library only; no source attachments copied.

DC answer keys use modified nodal analysis, checked by KCL and resistor power balance.
See docs/STUDY.md for conventions, provenance and the content review checklist.
"""
import json
import math
from pathlib import Path

EXERCISES = []


def circuit(nodes, parts):
    return {'nodes': [{'id': k, 'x': xy[0], 'y': xy[1]} for k, xy in nodes.items()],
            'parts': [dict(zip(('id', 'kind', 'from', 'to', 'value', 'unit'), p)) for p in parts]}


def r(i, a, b, v): return (f'R{i}', 'resistor', a, b, v, 'ohm')
def w(i, a, b): return (f'W{i}', 'wire', a, b, 0, '')
def v(i, a, b, n): return (f'V{i}', 'voltage', a, b, n, 'V')
def j(i, a, b, n): return (f'J{i}', 'current', a, b, n, 'A')


def solve(c, active=None, injection=None):
    """Ideal V sources: V(from)-V(to)=value; I sources: from -> to."""
    parent = {n['id']: n['id'] for n in c['nodes']}
    def root(a):
        while parent[a] != a: a = parent[a]
        return a
    for p in c['parts']:
        if p['kind'] == 'wire': parent[root(p['to'])] = root(p['from'])
    ground = root(c['nodes'][-1]['id'])
    ids = sorted({root(n) for n in parent} - {ground})
    ix = {n: i for i, n in enumerate(ids)}
    vs = [p for p in c['parts'] if p['kind'] == 'voltage']
    size = len(ids) + len(vs)
    a = [[0.] * (size + 1) for _ in range(size)]
    def idx(n): return ix.get(root(n))
    def add(row, col, amount):
        if row is not None and col is not None: a[row][col] += amount
    for p in c['parts']:
        x, y = idx(p['from']), idx(p['to'])
        on = active is None or p['id'] in active
        if p['kind'] == 'resistor':
            g = 1 / p['value']
            for q, t, sign in [(x,x,1),(y,y,1),(x,y,-1),(y,x,-1)]: add(q,t,g*sign)
        if p['kind'] == 'current':
            add(x,size,-p['value'] if on else 0); add(y,size,p['value'] if on else 0)
        if p['kind'] == 'voltage':
            z = len(ids) + vs.index(p)
            add(x,z,1); add(y,z,-1); add(z,x,1); add(z,y,-1)
            a[z][size] = p['value'] if on else 0
    if injection:
        add(idx(injection[0]),size,1); add(idx(injection[1]),size,-1)
    original = [row[:] for row in a]
    for col in range(size):
        pivot = max(range(col,size), key=lambda row: abs(a[row][col]))
        assert abs(a[pivot][col]) > 1e-12, 'Singular teaching circuit'
        a[col], a[pivot] = a[pivot], a[col]
        divisor = a[col][col]; a[col] = [x/divisor for x in a[col]]
        for row in range(size):
            if row != col:
                factor = a[row][col]
                a[row] = [x-factor*y for x,y in zip(a[row],a[col])]
    volts = {n: (a[idx(n)][size] if idx(n) is not None else 0.) for n in parent}
    solution = [row[size] for row in a]
    for row in original:
        assert math.isclose(sum(x*y for x,y in zip(row[:size],solution)),row[size],abs_tol=1e-9), 'KCL/source residual'
    power = 0.
    for p in c['parts']:
        drop = volts[p['from']]-volts[p['to']]
        if p['kind']=='resistor': power += drop*drop/p['value']
        if p['kind']=='voltage': power += drop*solution[len(ids)+vs.index(p)]
        if p['kind']=='current' and (active is None or p['id'] in active): power += drop*p['value']
    if injection: power -= volts[injection[0]]-volts[injection[1]]
    assert abs(power)<1e-7, 'Circuit power balance'
    return volts


def field(id, label, expected, unit='', explanation='', kind='numeric', options=None):
    return dict(id=id, label=label, kind=kind, unit=unit, options=options or [],
                expected=str(expected), explanation=explanation)


def exercise(id, title, topic, prompt, c, fields, steps, hint, difficulty='Developing', constants=None):
    EXERCISES.append(dict(id=id,title=title,topic=topic,difficulty=difficulty,prompt=prompt,
                          circuit=c,fields=fields,steps=steps,hint=hint,constants=constants or {}))


def resistance(id, title, nodes, parts, a, b, formula, hint, difficulty='Developing'):
    c=circuit(nodes,parts); volts=solve(c,injection=(a,b)); result=volts[a]-volts[b]
    exercise(id,title,'Resistance',f'Find the equivalent resistance measured between terminals {a} and {b}. All resistors are ideal.',c,
             [field('Req','Equivalent resistance',result,'ohm',f'{formula} = {result:.6g} ohm.')],
             ['Identify nodes joined by ideal wires before combining components.',formula,
              f'A 1 A test current into {a} and out of {b} gives {result:.6g} V between the terminals. R = V / I.'],hint,difficulty)


resistance('r-series','A three-resistor chain',{'A':(80,190),'N1':(280,190),'N2':(480,190),'B':(680,190)},
           [r(1,'A','N1',120),r(2,'N1','N2',180),r(3,'N2','B',330)],'A','B','R1 + R2 + R3','There is only one current path.','Foundation')
resistance('r-parallel','Three parallel paths',{'A':(80,80),'N1':(350,80),'N2':(640,80),'B':(80,330),'N3':(350,330),'N4':(640,330)},
           [r(1,'A','B',120),r(2,'N1','N3',240),r(3,'N2','N4',360),w(1,'A','N1'),w(2,'N1','N2'),w(3,'B','N3'),w(4,'N3','N4')],
           'A','B','1 / (1/R1 + 1/R2 + 1/R3)','All three resistors share the same two electrical nodes.','Foundation')
resistance('r-nested','A split after a series resistor',{'A':(80,80),'N':(350,80),'C':(640,80),'B':(80,330),'D':(350,330),'E':(640,330)},
           [r(1,'A','N',150),w(1,'N','C'),r(2,'N','D',300),r(3,'C','E',600),w(2,'B','D'),w(3,'D','E')],
           'A','B','R1 + R2*R3/(R2+R3)','Reduce the parallel pair first.')
resistance('r-two-strings','Parallel resistor strings',{'A':(80,80),'N':(350,80),'C':(640,80),'B':(80,330),'D':(350,330),'E':(640,330)},
           [r(1,'A','N',100),r(2,'N','C',200),w(1,'C','E'),r(3,'A','B',400),r(4,'B','D',200),w(2,'D','E')],
           'A','E','(R1+R2)*(R3+R4)/(R1+R2+R3+R4)','The terminal E joins two complete series paths.')
resistance('r-ladder','Two-stage ladder',{'A':(80,80),'N':(350,80),'C':(640,80),'B':(80,330),'D':(350,330),'E':(640,330)},
           [r(1,'A','N',100),r(2,'N','C',150),r(3,'C','E',450),r(4,'N','D',300),w(1,'B','D'),w(2,'D','E')],
           'A','B','R1 + R4*(R2+R3)/(R4+R2+R3)','Work from the far end of the ladder.')
resistance('r-triangle','A direct path and a detour',{'A':(90,300),'N':(370,70),'B':(650,300)},
           [r(1,'A','N',220),r(2,'N','B',330),r(3,'A','B',550)],'A','B',
           'R3*(R1+R2)/(R3+R1+R2)','One branch contains two resistors, the other one.')
resistance('r-port','Move the measurement terminals',{'A':(80,80),'N':(350,80),'C':(640,80),'B':(80,330),'D':(350,330),'E':(640,330)},
           [r(1,'A','N',100),r(2,'N','C',150),r(3,'C','E',450),r(4,'N','D',300),w(1,'B','D'),w(2,'D','E')],
           'C','B','R3*(R2+R4)/(R3+R2+R4); R1 ends at an open terminal and carries no current',
           'Terminal A is now open. A dangling branch does not carry test current.','Challenge')
resistance('r-bridge','A balanced bridge',{'A':(80,200),'N':(350,70),'M':(350,330),'B':(640,200)},
           [r(1,'A','N',100),r(2,'N','B',200),r(3,'A','M',150),r(4,'M','B',300),r(5,'N','M',470)],
           'A','B','R1/R2 = R3/R4, so N and M have equal potential; (R1+R2) || (R3+R4)',
           'Check the two divider ratios before trying to reduce the central resistor.','Challenge')

base_nodes={'S':(80,70),'A':(350,70),'C':(640,70),'G':(80,330),'B':(350,330),'D':(640,330)}
for id,title,values in [('dc-divider','Loaded voltage divider',(24,120,240,240)),('dc-supply','Resistor power budget',(12,60,180,360))]:
    vs,r1,r2,r3=values
    c=circuit(base_nodes,[v(1,'S','G',vs),r(1,'S','A',r1),r(2,'A','B',r2),r(3,'C','D',r3),w(1,'A','C'),w(2,'G','B'),w(3,'B','D')])
    u=solve(c); out=u['A']-u['B']; current=(u['S']-u['A'])/r1
    exercise(id,title,'Ohm and power',f'Find the output voltage Uab, current in R1 (S to A), and power dissipated by R1. V1 = {vs} V.',c,
             [field('U','Uab',out,'V','The load R3 is in parallel with R2.'),field('I','R1 current, S to A',current,'A','I1 = (V1 - Uab) / R1.'),field('P','R1 power',current**2*r1,'W','P1 = I1² R1. Power dissipated by a resistor is non-negative.')],
             [f'Rload = R2 || R3 = {r2*r3/(r2+r3):.6g} ohm.',f'Uab = V1 * Rload / (R1 + Rload) = {out:.6g} V.',f'I1 = {current:.6g} A; P1 = {current**2*r1:.6g} W.'],
             'A loaded divider uses the parallel combination of the lower resistor and load.')
c=circuit({'A':(130,80),'B':(610,80),'C':(610,330),'G':(130,330)},[v(1,'A','G',9),r(1,'A','B',300),r(2,'B','C',600),w(1,'C','G')])
exercise('dc-reverse','A negative current is meaningful','Ohm and power','Calculate current in R1 using the reference B to A, and voltage V(B) - V(A).',c,
         [field('I','R1 current, B to A',-.01,'A','The actual current runs from A to B, opposite to the requested reference.'),field('U','V(B) - V(A)',-3,'V','The requested voltage reference is opposite to the drop along the actual current.')],
         ['Total resistance is 900 ohm, so the physical current is 9/900 = 0.01 A from A to B.','Reversing a reference changes the sign: I = -0.01 A and U = -3 V.'],'Read the requested direction before calculating.','Foundation')
c=circuit(base_nodes,[j(1,'G','S',.03),w(1,'S','A'),w(2,'A','C'),r(1,'A','B',200),r(2,'C','D',400),w(3,'G','B'),w(4,'B','D')])
exercise('dc-current-divider','Sharing a current source','Ohm and power','Find currents through R1 and R2 from the upper rail to the lower rail, and the voltage between the rails.',c,
         [field('I1','R1 current',.02,'A','Current divides inversely with resistance.'),field('I2','R2 current',.01,'A','I2 = J1 * R1 / (R1 + R2).'),field('U','Upper rail voltage',4,'V','U = I1 * R1 = I2 * R2.')],
         ['I1 = 0.03 * 400 / 600 = 0.02 A.','I2 = 0.03 * 200 / 600 = 0.01 A.','Both branches have 4 V across them; 0.02 + 0.01 = 0.03 A.'],'The smaller resistance carries the larger current.','Foundation')

# Four equation tasks, with every variable and reference defined in the prompt.
eqnodes={'S':(80,70),'N':(270,70),'A':(470,70),'C':(680,70),'G':(80,340),'B':(470,340),'D':(680,340),'M':(470,200)}
c=circuit(eqnodes,[v(1,'S','G',24),r(1,'S','N',100),r(2,'N','A',200),r(3,'A','M',150),r(5,'M','B',250),r(4,'C','D',600),w(1,'A','C'),w(2,'G','B'),w(3,'B','D')])
fields=[field(f'ohm{i}',f'Ohm law for R{i}',f'U{i}=R{i}*{current}',explanation=f'U{i} is measured along {current}; use the passive sign convention.',kind='equation') for i,current in [(1,'I1'),(2,'I1'),(3,'I2'),(4,'I3'),(5,'I2')]]
fields += [field('kcla','KCL at A','I1-I2-I3=0',kind='equation',explanation='I1 enters A; I2 and I3 leave.'),field('kclb','KCL at B','-I1+I2+I3=0',kind='equation',explanation='This is the negative of the A equation, not an additional independent constraint.'),field('kvl1','KVL: source and middle branch','Vs-U1-U2-U3-U5=0',kind='equation',explanation='The voltage rise of the source equals the drops along the left/middle loop.'),field('kvl2','KVL: middle and right branches','U3+U5-U4=0',kind='equation',explanation='Both paths join A and B, so their total voltage drops agree.')]
exercise('eq-two-mesh','Write the complete equation set','Equations','Define I1 from S through R1 and R2 toward A; I2 from A through R3 and R5 to B; I3 from C through R4 to D. U1…U5 are resistor drops along those currents. Vs is the voltage of V1. Write five Ohm laws, KCL at A and B, and the two stated KVL loops. Use * for multiplication.',c,fields,
         ['Each resistor contributes U = R*I using its branch current.','At A: I1 = I2 + I3. At B the same conservation law has the opposite sign.','Left loop: Vs = U1 + U2 + U3 + U5. Right loop: U3 + U5 = U4.','There are two independent meshes; writing KCL for both essential nodes does not give two independent equations.'],
         'Do not give R1 and R2 different branch currents: no current leaves their joining node.','Challenge',{'R1':100,'R2':200,'R3':150,'R4':600,'R5':250,'Vs':24})
c2=circuit(base_nodes,[v(1,'S','G',18),r(1,'S','A',120),r(2,'A','B',240),r(3,'C','D',360),w(1,'A','C'),w(2,'G','B'),w(3,'B','D')])
exercise('eq-node','One node, three branches','Equations','I1 runs S to A, I2 runs A to B and I3 runs C to D. U1, U2 and U3 are drops along those currents. Vs is V1. Write the requested relations.',c2,
         [field('kcl','KCL at A','I1-I2-I3=0',kind='equation',explanation='One current enters and two leave.'),field('loop','KVL through R1 and R2','Vs-U1-U2=0',kind='equation',explanation='Follow the source rise, then both resistor drops.'),field('parallel','Equal parallel voltages','U2-U3=0',kind='equation',explanation='R2 and R3 connect the same two electrical nodes.')],
         ['KCL: I1 - I2 - I3 = 0.','KVL: Vs - U1 - U2 = 0.','The lower branches are parallel, so U2 = U3.'],'You may rearrange or multiply a complete equation by a non-zero constant.','Foundation',{'Vs':18})
exercise('eq-nodal','Build a nodal equation','Equations','Let U be the voltage at A relative to B; Vs is V1. Write KCL at A entirely in U, Vs and resistor values. Then express the R2 current I2 from A to B.',c2,
         [field('node','Nodal KCL','(U-Vs)/R1+U/R2+U/R3=0',kind='equation',explanation='Sum currents leaving A: (U - Vs)/R1, U/R2 and U/R3.'),field('ohm','R2 current','I2=U/R2',kind='equation',explanation='The bottom of R2 is the reference node.')],
         ['Translate each outgoing current into a voltage difference divided by resistance.','(U - Vs)/120 + U/240 + U/360 = 0.','Once U is known, I2 = U/240.'],'Choose all branch currents leaving the node, even if one result becomes negative.','Developing',{'R1':120,'R2':240,'R3':360,'Vs':18})
c3=circuit(base_nodes,[j(1,'G','S',.04),w(1,'S','A'),w(2,'A','C'),r(1,'A','B',300),r(2,'C','D',600),w(3,'G','B'),w(4,'B','D')])
exercise('eq-source','A current-source constraint','Equations','Js denotes J1. I1 and I2 run down R1 and R2. U is the upper-rail voltage relative to the lower rail. Write KCL, both Ohm laws, and a voltage-only node equation.',c3,
         [field('kcl','KCL at the upper rail','Js-I1-I2=0',kind='equation',explanation='The current source injects Js into the upper rail.'),field('r1','Ohm law for R1','U=R1*I1',kind='equation',explanation='R1 shares the rail voltage U.'),field('r2','Ohm law for R2','U=R2*I2',kind='equation',explanation='R2 shares the same rail voltage.'),field('node','Voltage-only KCL','U/R1+U/R2-Js=0',kind='equation',explanation='Replace the two resistor currents using Ohm law.')],
         ['Js = I1 + I2.','U = 300*I1 = 600*I2.','U/300 + U/600 = Js.'],'An ideal current source fixes current; its voltage is determined by the network.','Developing',{'R1':300,'R2':600,'Js':.04})


def superposition(id,title,c,target,hint,difficulty='Developing'):
    p=next(p for p in c['parts'] if p['id']==target)
    sources=[p for p in c['parts'] if p['kind'] in ('voltage','current')]
    fields=[]; steps=[]; partials=[]
    if id in ('sp-aiding','sp-opposing','sp-reversal'):
        steps += ['V1 alone: open J1. R1 and R2 are in series, so the downward R2 current is V1/(R1+R2).',
                  'J1 alone: short V1. R1 and R2 are parallel, so the R2 contribution has magnitude J1*R1/(R1+R2). Its sign is positive for injection into the upper node and negative for extraction.']
    elif id=='sp-two-voltage':
        steps += ['At A, KCL is (Ua-V1)/R1 + Ua/R2 + (Ua-V2)/R3 = 0.',
                  'Let G = 1/R1 + 1/R2 + 1/R3. Then Ua due to V1 is (V1/R1)/G; Ua due to V2 is (V2/R3)/G. Divide each by R2 for its load-current contribution.']
    elif id=='sp-clamped':
        steps += ['V1 fixes V(A)-V(B). With V1 alone, R2 and R3 form a series load: I_R3 = V1/(R2+R3).',
                  'With J1 alone, V1 is a short. Both ends of the R2-R3 series path are at the same potential, so its current is zero. The left source current returns through the short.']
    else:
        steps += ['With J1 alone, all its injected current returns through R1: I_R1 = J1.',
                  'With J2 alone, it extracts current from the upper rail, so I_R1 = -J2. The combined KCL equation is J1 - J2 - I_R1 = 0.']
    for source in sources:
        u=solve(c,{source['id']}); current=(u[p['from']]-u[p['to']])/p['value']; partials.append(current)
        fields.append(field(source['id'],f'{target} current: {source["id"]} alone',current,'A',f'Keep {source["id"]} active. Replace every other ideal voltage source by a short and every other ideal current source by an open circuit.'))
        steps.append(f'With {source["id"]} alone, V({p["from"]}) - V({p["to"]}) = {current*p["value"]:.6g} V, so I = {current:.6g} A.')
    u=solve(c); total=(u[p['from']]-u[p['to']])/p['value']
    assert math.isclose(sum(partials),total,rel_tol=1e-9,abs_tol=1e-10)
    fields += [field('total',f'{target} total current',total,'A','Add the signed contributions; do not add their magnitudes.'),field('power',f'{target} total power',total**2*p['value'],'W','Compute power from the total current: P = I_total² R. Individual source powers cannot be superposed.')]
    steps += [f'I_total = {" + ".join(f"({x:.6g})" for x in partials)} = {total:.6g} A.',f'P = ({total:.6g})² * {p["value"]} = {total**2*p["value"]:.6g} W.']
    exercise(id,title,'Superposition',f'Use superposition to find current in {target}, referenced from {p["from"]} to {p["to"]}, then its total power. Use the source selector to inspect each reduced circuit. All sources are independent and ideal.',c,fields,steps,hint,difficulty)

superposition('sp-aiding','Voltage and current cooperate',circuit(base_nodes,[v(1,'S','G',12),r(1,'S','A',100),r(2,'A','B',200),j(1,'D','C',.03),w(1,'A','C'),w(2,'G','B'),w(3,'B','D')]),'R2','For V1 alone use a divider; for J1 alone, R1 and R2 are parallel.')
superposition('sp-opposing','An opposing current source',circuit(base_nodes,[v(1,'S','G',12),r(1,'S','A',100),r(2,'A','B',200),j(1,'C','D',.09),w(1,'A','C'),w(2,'G','B'),w(3,'B','D')]),'R2','The current-source contribution is negative in the requested downward reference.')
superposition('sp-reversal','When the total current reverses',circuit(base_nodes,[v(1,'S','G',9),r(1,'S','A',300),r(2,'A','B',600),j(1,'C','D',.06),w(1,'A','C'),w(2,'G','B'),w(3,'B','D')]),'R2','A negative answer is valid. Keep the sign through the final sum.','Challenge')
superposition('sp-two-voltage','Two voltage sources, one load',circuit(base_nodes,[v(1,'S','G',18),r(1,'S','A',120),r(2,'A','B',240),r(3,'A','C',360),v(2,'C','D',6),w(2,'G','B'),w(3,'B','D')]),'R2','When one source is shorted, its series resistor remains in the circuit.','Challenge')
superposition('sp-clamped','The zero-contribution surprise',circuit(base_nodes,[j(1,'G','S',.02),r(1,'S','A',100),v(1,'A','B',15),r(2,'A','C',200),r(3,'C','D',300),w(2,'G','B'),w(3,'B','D')]),'R3','With V1 switched off, A is shorted to B. Can the left current source change the right-hand voltage?','Challenge')
superposition('sp-two-current','Two current sources meet',circuit(base_nodes,[j(1,'G','S',.04),w(1,'S','A'),r(1,'A','B',150),w(2,'A','C'),j(2,'C','D',.01),w(3,'G','B'),w(4,'B','D')]),'R1','Switching off a current source opens its branch. The remaining current source drives R1.')

# AC calculations are explicitly extra practice, not part of the reported three calculation slots.
for id,title,kind,value,unit,freq,result,formula in [
    ('ac-capacitor','Capacitive reactance','capacitor',10e-6,'F',50,1/(2*math.pi*50*10e-6),'Xc = 1/(2*pi*f*C)'),
    ('ac-inductor','Inductive reactance','inductor',.08,'H',400,2*math.pi*400*.08,'XL = 2*pi*f*L')]:
    c=circuit({'A':(150,190),'B':(610,190)},[('C1' if kind=='capacitor' else 'L1',kind,'A','B',value,unit)])
    exercise(id,title,'AC extension',f'At {freq} Hz, calculate the magnitude of the ideal component reactance. Give a positive magnitude, not complex impedance.',c,
             [field('X','Reactance magnitude',result,'ohm',f'{formula} = {result:.6g} ohm.')],
             [f'Convert the value to SI units: {value:g} {unit}.',f'omega = 2*pi*{freq} rad/s.',f'{formula} = {result:.6g} ohm.'],
             'Do not confuse frequency f with angular frequency omega.','Developing')
exercise('ac-rms','Peak is not RMS','AC extension','A sinusoidal voltage has a peak amplitude of 170 V and drives a 100 ohm resistor. Find RMS voltage and average resistor power.',None,
         [field('V','RMS voltage',170/math.sqrt(2),'V','For a sine wave, Vrms = Vpeak / sqrt(2).'),field('P','Average power',170**2/2/100,'W','Paverage = Vrms²/R, not Vpeak²/R.')],
         ['Vrms = 170/sqrt(2) = 120.208 V.','Paverage = 120.208²/100 = 144.5 W.'],'The peak-to-RMS conversion here applies specifically to a sine wave.','Foundation')
exercise('ac-charge','Charge and stored energy','AC extension','An ideal 47 microfarad capacitor is charged to 12 V. Calculate its stored charge and energy.',None,
         [field('Q','Stored charge',47e-6*12,'C','Q = C*V. Convert microfarads to farads.'),field('E','Stored energy',.5*47e-6*12**2,'J','E = C*V²/2.')],
         ['C = 47e-6 F.','Q = 47e-6 * 12 = 0.000564 C.','E = 0.5 * 47e-6 * 12² = 0.003384 J.'],'Use SI units throughout.','Foundation')

theory=[
 ('ohm','What does Ohm law state?','For an ideal resistor at fixed conditions, which relation holds?', ['U = R*I','U = R/I','I = R*U','R = U*I'],0,'Voltage drop is proportional to current, with resistance as the proportionality factor.'),
 ('kcl','Why does KCL work?','The algebraic sum of currents at a node is zero because of:', ['Conservation of charge','Conservation of resistance','Equal branch currents','Zero node voltage'],0,'Charge does not accumulate at an ideal circuit node; entering and leaving currents balance.'),
 ('kvl','Which loop statement is valid?','For a lumped circuit with no unmodelled time-varying magnetic flux, KVL says:', ['The algebraic voltage sum around a closed loop is zero','Every resistor has the same voltage','All loop currents are equal','Every source voltage is zero'],0,'Use a consistent traversal direction and include source rises and element drops.'),
 ('sources','Deactivate sources correctly','For superposition with independent ideal sources, how are inactive sources replaced?', ['Voltage source: short; current source: open','Voltage source: open; current source: short','Both: short','Both: open'],0,'Setting ideal voltage to zero constrains its terminals to equal potential. Setting ideal current to zero blocks its branch.'),
 ('power','Can powers be superposed?','Two source contributions to resistor current are I_a and I_b. Total resistor power is:', ['(I_a + I_b)^2 * R','I_a^2 * R + I_b^2 * R','(I_a - I_b) * R','Always zero'],0,'Voltage and current are linear responses. Power is quadratic, so use the signed total current before squaring.'),
 ('capacitor','Capacitor in sinusoidal steady state','In an ideal capacitor, the current:', ['Leads the voltage by 90 degrees','Lags the voltage by 90 degrees','Is always in phase with voltage','Must always be zero'],0,'i = C*dV/dt. For a sine-wave voltage, differentiation gives a quarter-cycle lead.'),
 ('inductor','Inductor in sinusoidal steady state','In an ideal inductor, the voltage:', ['Leads the current by 90 degrees','Lags the current by 90 degrees','Is always in phase with current','Is unrelated to frequency'],0,'v = L*dI/dt; the voltage leads sinusoidal current by a quarter cycle.'),
 ('dc-storage','Ideal storage elements after settling','In DC steady state, an ideal capacitor and ideal inductor behave as:', ['Capacitor: open; inductor: short','Capacitor: short; inductor: open','Both: open','Both: short'],0,'Constant capacitor voltage gives zero capacitor current. Constant inductor current gives zero inductor voltage. This is a steady-state statement, not the switching transient.'),
 ('thevenin','Recognize a Thevenin equivalent','A linear two-terminal network can be represented by:', ['An ideal voltage source in series with an equivalent resistance','An ideal voltage source in parallel with a resistance','Only an ideal current source','A resistor that must equal the load'],0,'Thevenin voltage is the open-circuit terminal voltage. Equivalent resistance is seen looking into the unloaded network; independent sources are deactivated for the passive resistance method.'),
 ('norton','Connect Norton and Thevenin','For matching terminal references and a finite positive equivalent resistance:', ['R_N = R_Th and V_Th = I_N * R_Th','R_N = 1/R_Th','V_Th = I_N/R_Th','I_N always equals the load current'],0,'Norton uses an ideal current source in parallel with the equivalent resistance. I_N is the short-circuit terminal current under the chosen reference convention.'),
 ('dependent','A source-deactivation caveat','When finding an equivalent resistance in a network containing dependent sources:', ['Keep dependent sources active and use a test source if necessary','Deactivate every dependent source','Always remove all sources','Ignore the controlling variable'],0,'Only independent sources are deactivated. A dependent source still obeys its controlling relation.'),
 ('meter','Connect an ideal meter','How should voltage and current be measured?', ['Voltmeter in parallel; ammeter in series','Both in series','Both in parallel','Voltmeter in series; ammeter in parallel'],0,'An ideal voltmeter has infinite resistance; an ideal ammeter has zero resistance. Real instruments approximate these limits.'),
]
for index,(id,title,prompt,options,answer,why) in enumerate(theory):
    expected=options[answer]
    # Rotate answer positions deterministically; do not train a first-option shortcut.
    shift=index%len(options); options=options[shift:]+options[:shift]
    exercise('theory-'+id,title,'Theory',prompt,None,[field('choice','Choose one answer',expected,kind='choice',options=options,explanation=why)], [why], 'Explain the physical law before comparing the choices.','Foundation')

lessons=[
 ('Ohm law and references','U = R I; P = U I = I²R','Choose a current arrow and measure the resistor voltage drop along it. A negative result means the actual direction is opposite. Use ohm, volt and ampere consistently.','A negative current is not an error. A resistor still dissipates non-negative power.'),
 ('Equivalent resistance','Rseries = sum(R); 1/Rparallel = sum(1/R)','Series elements share the same current with no branch at their joining node. Parallel elements share both electrical nodes. Redraw wires as nodes before reducing a network.','Visual proximity does not prove series or parallel. Changing the measurement terminals can change the answer.'),
 ('Kirchhoff current law','Sum of signed currents at a node = 0','Pick entering currents positive or leaving currents positive and stay consistent. In a connected network, one node equation is dependent on the others.','Writing both A and B equations for a two-node network does not create two independent equations.'),
 ('Kirchhoff voltage law','Sum of signed voltages around a closed loop = 0','Choose a loop direction. A voltage rise and a voltage drop have opposite signs. Write Ohm law for each resistor and enough independent node/loop constraints.','The ideal current source voltage is generally unknown. Do not pretend it has zero voltage.'),
 ('Superposition','Total response = signed sum of single-source responses','In a linear circuit, leave one independent source active at a time. Replace other ideal voltage sources by shorts and current sources by opens. Keep resistors and dependent sources.','Add voltage or current contributions, then calculate power. Do not add the separately calculated powers.'),
 ('Capacitors and inductors','Q = C V; iC = C dV/dt; vL = L dI/dt','Capacitors store electric-field energy; inductors store magnetic-field energy. In settled ideal DC, a capacitor is open and an inductor is short.','The steady-state simplification does not describe the switching transient.'),
 ('Sinusoidal AC','omega = 2*pi*f; ZC = 1/(j*omega*C); ZL = j*omega*L','Resistor voltage and current are in phase. Capacitor current leads its voltage by 90 degrees; inductor current lags its voltage by 90 degrees. For a sine wave, RMS = peak/sqrt(2).','Reactance magnitude is positive, while capacitor impedance has a negative imaginary part. The sine-wave RMS conversion is not universal.'),
 ('Thevenin and Norton','VTh = Voc; IN = Isc; RN = RTh; VTh = IN RTh','A linear two-terminal network has a voltage-source/series-resistance or current-source/parallel-resistance equivalent. These reproduce terminal behavior; they do not reproduce every internal branch.','Disconnect the load when finding the equivalent. Keep dependent sources active; use a test source when needed. This section is theory-focused for the reported exam.'),
]
subject=dict(id='basics-of-engineering',title='Basics of (Mechanical) Engineering',version='2026.10.13-1',
             lessons=[dict(zip(('title','formula','text','trap'),x)) for x in lessons],exercises=EXERCISES)
path=Path(__file__).resolve().parents[1]/'src/main/resources/study/engineering-v1.json'
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(json.dumps(subject,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'Generated {len(EXERCISES)} original exercises: '+', '.join(f'{t}: {sum(e["topic"]==t for e in EXERCISES)}' for t in dict.fromkeys(e['topic'] for e in EXERCISES)))
