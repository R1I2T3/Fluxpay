import {activityType,movementDirection} from './activity';

// Simplified Natural Earth 1:110m land geometry (public domain), projected locally.
// Source: https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_110m_land.geojson
// No third-party map requests, location collection, or geopolitical boundary claims.
export const worldShapes=[
  'M178.9,41.0 L185.3,45.6 L189.0,40.2 L194.8,40.7 L197.5,44.8 L173.7,56.0 L170.6,62.1 L175.4,65.8 L195.5,69.7 L200.2,77.6 L202.8,74.9 L200.3,70.7 L206.9,66.9 L203.0,62.4 L205.3,60.3 L203.8,55.4 L212.3,55.1 L220.8,57.9 L224.7,63.6 L230.8,59.3 L236.4,67.3 L245.3,70.7 L248.6,75.7 L239.9,79.5 L227.2,79.5 L217.8,86.4 L229.9,81.5 L231.1,87.5 L240.4,88.2 L229.3,92.9 L227.7,91.1 L231.1,89.4 L225.7,89.7 L218.6,93.9 L220.1,96.7 L208.9,101.0 L208.1,105.6 L207.3,101.7 L208.5,108.9 L197.3,117.1 L199.2,129.6 L191.8,119.8 L166.8,123.4 L164.3,135.1 L167.4,141.4 L175.9,142.6 L179.4,138.0 L185.9,136.9 L182.1,148.2 L193.2,149.5 L192.4,157.8 L197.1,162.4 L206.3,162.7 L216.5,155.1 L216.6,161.9 L220.1,155.7 L223.6,158.9 L236.2,158.6 L245.7,168.1 L257.4,171.6 L259.2,180.2 L280.0,185.7 L288.8,190.3 L290.5,194.7 L282.7,206.1 L278.1,223.9 L264.7,229.8 L252.4,248.8 L243.1,247.8 L246.4,253.8 L229.8,262.1 L233.1,265.1 L225.4,271.1 L228.0,276.3 L221.7,281.5 L223.7,284.7 L218.0,287.7 L210.1,284.5 L208.8,277.3 L211.7,273.9 L208.7,273.3 L214.6,264.8 L211.3,266.4 L219.7,219.5 L208.0,209.3 L197.5,192.3 L200.5,185.3 L198.1,182.1 L205.7,172.3 L203.6,163.4 L200.9,162.1 L198.2,165.6 L188.7,160.1 L185.0,153.4 L153.0,143.4 L130.4,116.4 L141.1,133.6 L135.6,130.5 L125.4,113.9 L118.8,110.8 L111.2,99.4 L110.6,83.6 L114.8,85.8 L114.3,82.0 L105.1,78.3 L91.8,63.8 L65.8,58.2 L56.6,61.7 L58.8,57.4 L43.1,68.0 L30.4,71.2 L45.9,62.2 L36.1,62.7 L27.8,57.0 L38.4,50.4 L23.8,48.7 L36.6,47.8 L26.5,43.3 L46.8,37.3 L87.0,42.2 L103.7,39.0 L142.2,45.2 L147.7,42.4 L167.7,45.4 L171.5,41.9 L167.1,39.8 L169.6,36.2 L178.9,41.0Z',
  'M573.9,26.1 L588.3,28.3 L578.8,31.6 L614.0,32.9 L622.6,38.4 L639.7,37.0 L640.9,34.3 L699.2,42.6 L700.9,39.8 L711.4,40.2 L720.0,42.1 L720.0,50.0 L714.8,50.8 L718.5,55.4 L687.1,60.3 L684.2,70.3 L673.6,78.0 L671.8,66.5 L688.9,54.9 L680.2,58.9 L673.4,57.1 L670.1,61.7 L644.4,61.9 L630.3,70.5 L639.8,71.6 L642.8,75.5 L636.4,87.4 L615.1,100.5 L618.2,109.8 L613.0,111.2 L610.6,100.9 L602.1,102.2 L603.3,98.1 L596.1,101.6 L597.8,105.1 L604.7,105.1 L598.3,110.2 L603.8,116.6 L603.4,123.5 L591.8,134.4 L580.9,139.3 L577.0,136.6 L571.8,140.5 L578.7,153.1 L570.3,162.8 L560.2,153.2 L558.4,161.5 L565.9,169.0 L568.5,177.4 L562.8,174.5 L556.7,164.4 L554.3,146.1 L548.4,147.9 L542.8,134.5 L534.0,137.0 L520.6,148.2 L519.7,159.3 L515.1,164.1 L505.3,137.3 L500.9,138.2 L492.7,129.1 L474.8,128.5 L455.9,120.0 L463.6,132.0 L472.7,127.2 L479.6,135.4 L470.5,145.5 L447.0,154.7 L445.3,146.5 L429.8,121.0 L427.8,124.7 L424.8,120.3 L435.0,142.8 L445.4,156.5 L449.2,159.1 L462.2,156.0 L462.1,158.7 L455.5,171.6 L438.4,189.4 L441.6,209.4 L429.6,219.6 L431.2,227.4 L425.1,231.5 L424.4,237.5 L411.6,247.9 L396.8,248.3 L383.6,216.1 L387.4,201.5 L377.6,182.2 L378.8,172.5 L371.8,171.5 L368.7,167.5 L342.0,170.3 L326.8,155.7 L326.1,136.2 L348.1,108.5 L379.0,105.3 L382.2,106.2 L380.7,112.4 L398.2,119.5 L403.1,114.3 L427.5,118.1 L432.3,106.7 L415.3,106.7 L412.3,101.1 L427.0,96.0 L443.4,96.1 L433.4,89.5 L438.2,85.5 L429.9,87.5 L432.7,89.8 L427.8,91.3 L426.6,87.8 L421.5,86.8 L415.3,94.8 L417.6,97.9 L405.3,99.5 L408.1,104.7 L405.0,107.2 L399.1,96.6 L386.3,88.5 L385.2,91.8 L397.0,99.7 L393.7,99.1 L392.2,104.0 L390.8,99.9 L377.8,91.3 L366.2,93.8 L355.7,106.7 L342.2,106.3 L341.2,93.9 L357.2,92.0 L357.6,88.0 L350.8,82.6 L356.8,82.7 L356.1,80.4 L376.2,72.9 L377.1,65.8 L381.2,64.5 L379.3,69.1 L381.9,72.0 L399.3,71.1 L403.2,65.2 L408.2,65.9 L406.7,61.6 L418.2,59.9 L402.6,58.6 L403.1,53.6 L410.8,49.8 L404.4,48.6 L395.7,54.5 L394.2,57.3 L397.6,59.8 L391.8,67.8 L385.9,69.3 L380.7,61.1 L371.3,62.8 L370.0,56.1 L389.5,44.4 L409.1,37.9 L442.1,45.1 L436.8,48.0 L426.4,46.7 L434.0,52.3 L434.4,49.7 L447.9,47.9 L446.9,42.9 L452.5,43.5 L452.7,46.7 L467.4,42.3 L479.9,43.4 L481.1,40.3 L497.0,43.8 L493.4,37.9 L505.2,34.4 L507.3,43.2 L502.6,47.4 L504.8,47.7 L510.1,44.5 L506.2,37.1 L509.3,34.3 L512.7,37.7 L523.0,36.5 L521.0,32.7 L533.6,32.1 L534.3,29.8 L573.9,26.1Z',
  'M305.8,13.0 L318.3,14.5 L296.2,15.6 L335.6,17.4 L319.9,19.6 L324.5,19.7 L320.6,22.5 L323.1,26.0 L316.6,26.7 L321.3,31.4 L310.4,35.3 L316.5,38.7 L308.9,37.1 L307.3,39.5 L315.3,39.7 L280.4,49.1 L273.2,59.8 L263.5,58.3 L256.7,52.7 L252.1,45.6 L258.3,40.1 L250.6,40.8 L251.3,38.4 L257.2,38.9 L248.3,36.7 L250.6,34.8 L242.8,29.0 L223.0,27.9 L217.2,26.0 L226.5,25.2 L213.4,23.9 L228.6,21.2 L224.0,19.8 L234.7,16.5 L305.8,13.0Z',
  'M647.1,207.5 L666.3,232.1 L665.8,243.3 L660.0,254.9 L652.6,258.1 L641.3,256.0 L636.4,248.8 L633.7,250.5 L635.6,245.8 L632.0,249.8 L622.7,243.0 L596.0,250.1 L590.1,248.4 L591.4,243.2 L586.7,232.2 L588.3,223.5 L601.7,219.4 L611.4,208.5 L619.2,209.9 L624.7,202.3 L633.0,203.7 L631.0,210.0 L640.4,215.4 L645.0,201.3 L647.1,207.5Z',
  'M706.0,261.8 L708.5,262.7 L706.2,267.7 L698.7,273.3 L693.4,272.4 L706.0,261.8Z',
  'M709.2,252.3 L717.0,255.4 L710.5,263.4 L705.3,249.1 L709.2,252.3Z',
  'M460.1,207.1 L454.2,229.9 L448.1,230.0 L448.9,212.4 L458.4,204.1 L460.1,207.1Z',
  'M628.3,182.3 L630.9,186.7 L636.7,183.4 L649.2,187.7 L661.4,201.2 L649.5,195.3 L645.3,198.7 L635.2,196.8 L635.9,190.8 L626.0,188.2 L624.0,185.6 L627.4,184.4 L621.0,181.9 L628.3,182.3Z',
  'M595.8,176.3 L598.0,178.2 L592.3,188.0 L580.4,185.9 L578.2,180.9 L579.3,176.0 L593.5,166.2 L598.4,169.2 L595.8,176.3Z',
  'M571.6,191.7 L565.2,188.4 L550.6,169.0 L567.7,179.8 L572.2,186.1 L571.6,191.7Z',
  'M642.0,105.7 L640.5,109.7 L631.6,113.1 L630.2,110.8 L622.0,112.2 L624.0,113.7 L620.4,117.2 L618.8,113.4 L631.4,108.9 L642.7,97.2 L642.0,105.7Z',
  'M354.0,62.7 L351.9,64.9 L356.1,64.6 L353.8,68.1 L363.4,74.5 L362.9,77.4 L349.5,80.1 L353.2,77.1 L349.5,76.0 L350.8,73.0 L354.1,72.0 L347.7,66.4 L354.0,62.7Z',
  'M331.0,47.1 L332.8,49.7 L322.7,53.0 L311.3,48.8 L331.0,47.1Z',
  'M200.6,134.5 L211.6,139.4 L204.5,140.3 L196.4,134.7 L190.1,136.2 L200.6,134.5Z',
  'M186.9,33.7 L195.4,32.5 L198.5,35.9 L215.5,36.9 L226.1,41.6 L222.4,42.6 L236.3,46.3 L232.2,50.0 L224.0,47.5 L230.7,53.2 L222.4,52.5 L227.7,56.1 L204.6,51.5 L204.2,49.4 L212.1,49.1 L214.1,44.5 L202.1,39.7 L180.2,37.6 L181.1,33.7 L186.9,33.7Z',
  'M223.0,13.8 L236.3,14.7 L206.2,21.4 L209.2,22.9 L198.9,27.6 L181.0,27.1 L183.5,24.2 L190.0,24.9 L184.1,23.3 L189.8,21.3 L186.1,19.5 L196.3,19.1 L176.8,16.2 L223.0,13.8Z'
];
// Approximate country centres for a decorative overview, never a street-level location.
const centres:Record<string,[number,number]>={US:[-98,39],CA:[-106,57],MX:[-102,24],BR:[-52,-12],AR:[-64,-35],GB:[-2,54],IE:[-8,53],DE:[10,51],FR:[2,47],IT:[12,43],ES:[-4,40],PT:[-8,40],NL:[5,52],BE:[4,51],AT:[14,48],CH:[8,47],FI:[26,64],GR:[24,39],LU:[6,50],MT:[14,36],CY:[33,35],EE:[26,59],LV:[25,57],LT:[24,55],SK:[19,49],SI:[15,46],IN:[79,23],SG:[104,1],AE:[54,24],JP:[138,37],AU:[134,-25],NZ:[174,-41],CN:[104,35],ID:[118,-3],PH:[123,12],TH:[101,15],MY:[102,4],ZA:[25,-29],NG:[8,9],KE:[38,0],EG:[30,27],SA:[45,24],PK:[69,30],BD:[90,24],NP:[84,28],LK:[81,8],VN:[108,16],KR:[128,36],TR:[35,39]};
export function recipientCountries(recipients:any[],payments:any[]){
  const countries=new Map<string,{code:string;name:string;people:number;payments:number;point?:{x:number;y:number}}>();
  const names=new Intl.DisplayNames(['en'],{type:'region'});
  for(const recipient of recipients){
    if(recipient.status!=='ACTIVE')continue;
    const code=String(recipient.country||'').toUpperCase();if(!/^[A-Z]{2}$/.test(code))continue;
    if(!countries.has(code)){const centre=centres[code];countries.set(code,{code,name:names.of(code)||code,people:0,payments:0,point:centre?{x:(centre[0]+180)*2,y:(90-centre[1])*2}:undefined});}
    const row=countries.get(code)!;row.people++;row.payments+=payments.filter(p=>p.recipientId===recipient.id&&p.status==='COMPLETED').length;
  }
  return [...countries.values()].sort((a,b)=>b.payments-a.payments||b.people-a.people||a.name.localeCompare(b.name));
}
export function periodRecords(rows:any[],days:number,now:number){
  const start=new Date(now);start.setHours(0,0,0,0);start.setDate(start.getDate()-days+1);
  return rows.filter(row=>{const date=Date.parse(row.createdAt);return Number.isFinite(date)&&date>=start.getTime()&&date<=now;});
}
export function moneyFlow(payments:any[],entries:any[],currency:string,days:number,now:number){
  const start=new Date(now);start.setHours(0,0,0,0);start.setDate(start.getDate()-days+1);
  const buckets=Array.from({length:days},(_,index)=>{const day=new Date(start);day.setDate(start.getDate()+index);return {date:day.toLocaleDateString('en',{month:'short',day:'numeric'}),stamp:day.getTime(),incoming:0,outgoing:0};});
  const rows=periodRecords([...payments,...entries],days,now).filter(row=>row.status==='COMPLETED'&&row.sourceCurrency===currency);
  let count=0;
  for(const row of rows){
    const amount=Number(row.sourceAmount);if(!Number.isFinite(amount)||amount<=0)continue;
    const direction=row.isLedger?movementDirection(row):'Money out';if(!direction)continue;
    const day=new Date(row.createdAt);day.setHours(0,0,0,0);const bucket=buckets.find(b=>b.stamp===day.getTime());if(!bucket)continue;
    const key=direction==='Money in'?'incoming':'outgoing';bucket[key]=Math.round((bucket[key]+amount)*10000)/10000;count++;
  }
  const total=(key:'incoming'|'outgoing')=>Math.round(buckets.reduce((sum,b)=>sum+b[key],0)*10000)/10000;
  const categories=[['WALLET_TOPUP','Money added','#30b692'],['SEND_MONEY','Send money','#4d7ce8'],['WALLET_TO_WALLET','Wallet transfers','#9472df'],['SELF_TRANSFER','Exchanges','#eab45b'],['WITHDRAWAL','Withdrawals','#ee8c8a']].map(([type,label,color])=>({label,color,count:rows.filter(row=>activityType(row)===type).length}));
  return {buckets,incoming:total('incoming'),outgoing:total('outgoing'),count,categories};
}
export function chartPath(values:number[],maximum:number,width=600,height=150){
  const pad=8,scale=Math.max(1,maximum);
  return values.map((value,index)=>(index?'L':'M')+(pad+index*(width-2*pad)/Math.max(1,values.length-1)).toFixed(2)+','+(height-pad-Math.max(0,value)/scale*(height-pad*2)).toFixed(2)).join(' ');
}
export function transferStatus(payments:any[]){
  const groups=[{label:'Completed',color:'#4e7ce8',statuses:['COMPLETED']},{label:'In progress',color:'#e8b358',statuses:['PROCESSING','QUOTED','UNDER_REVIEW']},{label:'Drafts',color:'#b9c5d8',statuses:['DRAFT']},{label:'Needs attention',color:'#ec8f92',statuses:['FAILED','REJECTED']},{label:'Closed / refunded',color:'#8f7acb',statuses:['CANCELLED','REFUNDED']}];
  const total=payments.length;let angle=0;
  const rows=groups.map(group=>{const count=payments.filter(p=>group.statuses.includes(p.status)).length;const from=angle;angle+=total?count/total*360:0;return {...group,count,segment:`${group.color} ${from}deg ${angle}deg`};});
  return {total,completed:rows[0].count,rows,background:total?`conic-gradient(${rows.map(row=>row.segment).join(',')},#edf1f8 ${angle}deg 360deg)`:'#edf1f8'};
}
