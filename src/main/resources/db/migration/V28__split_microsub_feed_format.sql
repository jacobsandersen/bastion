-- The XML format is now RSS or ATOM. Existing rows are marked RSS and corrected by their next fetch.
update microsub_feeds set format = 'RSS' where format = 'XML';
