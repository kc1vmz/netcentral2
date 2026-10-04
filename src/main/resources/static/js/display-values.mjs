export function label(key){if(key==='destination')return 'Software';return key.replace(/([a-z])([A-Z])/g,'$1 $2').replace(/_/g,' ').replace(/^./,c=>c.toUpperCase());}
export function localTime(value){
  const date=new Date(value);if(!value||Number.isNaN(date.getTime()))return value||'Unknown';
  return new Intl.DateTimeFormat(undefined,{year:'numeric',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit',second:'2-digit',timeZoneName:'short'}).format(date);
}
export function displayValue(key,value){
  if(key==='symbol'&&(value==null||value===''))return 'Not specified';
  if(value==null)return 'Unknown';
  if(key==='lastHeard'||key==='recordedAt')return localTime(value);
  if(key==='types'&&Array.isArray(value))return value.map(type=>String(type).replace(/_/g,' ')).join(', ');
  if(typeof value==='boolean')return value?'Yes':'No';
  if(Array.isArray(value))return value.map(item=>displayValue('',item)).join(', ');
  if(typeof value==='object')return Object.entries(value).map(([k,v])=>`${label(k)}: ${displayValue(k,v)}`).join('; ');
  return String(value);
}
