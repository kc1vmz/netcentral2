/* Expected callsigns match participant base callsigns, including prior check-outs. */
function netParticipantRows(participants, expected) {
  const roots=new Set(participants.map(p=>p.callsign.split('-')[0].toUpperCase()));
  const missing=[...new Set((expected||[]).map(call=>call.split('-')[0].toUpperCase()))]
    .filter(call=>!roots.has(call)).sort().map(callsign=>({callsign,status:'Expected',expectedOnly:true}));
  return [...participants,...missing];
}
if(typeof module!=='undefined')module.exports=netParticipantRows;
