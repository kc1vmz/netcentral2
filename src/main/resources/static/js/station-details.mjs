import {label,displayValue,localTime} from './display-values.mjs';
for(const element of document.querySelectorAll('[data-field-label]'))element.textContent=label(element.textContent);
for(const element of document.querySelectorAll('[data-field]')){
  if(['rawLatitude','rawLongitude'].includes(element.dataset.field))element.style.whiteSpace='pre-wrap';
  try{element.textContent=displayValue(element.dataset.field,JSON.parse(element.dataset.value));}catch{}
}
for(const element of document.querySelectorAll('time[datetime]'))element.textContent=localTime(element.dateTime);
